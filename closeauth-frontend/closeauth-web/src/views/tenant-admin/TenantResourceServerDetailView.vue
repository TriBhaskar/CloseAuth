<script setup lang="ts">
// Stage UI-3c: resource server detail — editable name/slug, a read-only
// audience (never rendered as an input; it's immutable after creation, see
// UpdateResourceServerPayload's doc comment), and full scope-catalog CRUD.
//
// Scope edit is the one genuine gotcha here: PATCH .../scopes/{id} is a
// FULL REPLACEMENT of description/isDefault/requiresConsent, not a sparse
// merge (ResourceServerService.updateScope sets all three unconditionally)
// — the edit dialog below always pre-populates and always sends all three,
// never a partial object. scopeName is immutable and rendered read-only in
// that same dialog (offering an editable field that silently doesn't apply
// would be worse than not offering it at all).
//
// Delete (both the RS itself and any scope) is gated by resourceServerActions
// — an auto-created RS's delete control is replaced by an explanation
// instead of a button that would just 409.
//
// Stage UI-3d adds the application-roles panel (ApplicationRolesPanel.vue)
// below the scopes panel.
//
// FE-4b: the scope catalog table is rebuilt onto DataTable, gains the
// "used by N roles" column (usedByRoleCount, server-decorated — see
// tenantAdminResourceServers.ts's ScopeView doc comment; the client-grant
// half of spec §6.4.4's "where used" is a tracked, deferred gap, disclosed
// once above the table rather than faked per-row). Scope name is displayed
// as spec's literal `slug:scope` even though storage is bare (the prefix is
// applied at token issuance, not stored — ResourceServerScope's own doc
// comment). No server-side scope search exists (GET .../scopes still takes
// only page/size), so — same FE-3a/FE-4b precedent as the resource-servers
// list — a larger page is fetched once and DataTable's search filters it
// client-side.
import { computed, h, onMounted, reactive, ref, type VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import ApplicationRolesPanel from '@/components/admin/ApplicationRolesPanel.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import {
  addScope,
  deleteResourceServer,
  deleteScope,
  getResourceServer,
  listScopes,
  resourceServerActions,
  updateResourceServer,
  updateScope,
  RESOURCE_SERVER_CONFLICT_FIELDS,
  type ResourceServerView,
  type ScopeView,
} from '@/api/tenantAdminResourceServers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const rsId = String(route.params.rsId ?? '')

// ---- resource server -------------------------------------------------

const rs = ref<ResourceServerView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)

async function loadRS(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getResourceServer(slug, rsId)
  switch (result.kind) {
    case 'ok':
      rs.value = result.value
      resetEditForm(result.value)
      isLoading.value = false
      break
    case 'reauth':
      break
    default: {
      rs.value = null
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

onMounted(loadRS)

function backToList(): void {
  void router.push({ name: 'tenant-admin-resource-servers', params: { slug } })
}

const actions = computed(() => (rs.value ? resourceServerActions(rs.value) : []))

// ---- edit name/slug -------------------------------------------------

const editForm = reactive({ name: '', slug: '' })
const editErrors = reactive<Record<string, string>>({})
const editBanner = ref('')
const isSaving = ref(false)

function resetEditForm(value: ResourceServerView): void {
  editForm.name = value.name
  editForm.slug = value.slug
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]
}

async function handleSaveEdit(): Promise<void> {
  if (isSaving.value || !rs.value) return
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]

  isSaving.value = true
  try {
    const result = await updateResourceServer(slug, rsId, {
      name: editForm.name,
      slug: editForm.slug,
    })
    switch (result.kind) {
      case 'ok':
        rs.value = result.value
        break
      case 'validationErrors':
        Object.assign(editErrors, result.errors)
        break
      case 'conflict': {
        const field = RESOURCE_SERVER_CONFLICT_FIELDS[result.code]
        if (field) editErrors[field] = result.message
        else editBanner.value = result.message
        break
      }
      case 'reauth':
        break
      default:
        editBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isSaving.value = false
  }
}

// ---- delete resource server -------------------------------------------

const isDeleteConfirmOpen = ref(false)
const isDeleting = ref(false)
const deleteError = ref('')

async function confirmDelete(): Promise<void> {
  if (isDeleting.value) return
  deleteError.value = ''
  isDeleting.value = true
  try {
    const result = await deleteResourceServer(slug, rsId)
    switch (result.kind) {
      case 'ok':
        backToList()
        break
      case 'conflict':
        deleteError.value = result.message
        isDeleteConfirmOpen.value = false
        break
      case 'reauth':
        break
      default:
        deleteError.value = describeAdminError(result)
        isDeleteConfirmOpen.value = false
        break
    }
  } finally {
    isDeleting.value = false
  }
}

// ---- scopes -----------------------------------------------------------

// The whole catalog in one page, same convention as tenantAdminRoles.ts's
// ROLE_CATALOG_PAGE_SIZE — no server-side scope search exists, so DataTable
// filters this client-side (scopeSearchQuery below).
const SCOPE_CATALOG_PAGE_SIZE = 100

const scopes = ref<ScopeView[]>([])
// FE-6.1: was fetched and stored but never READ anywhere — only page 0 of
// SCOPE_CATALOG_PAGE_SIZE is ever requested, so a catalog past that size was
// silently truncated with no notice at all. Now drives the notice below,
// same pattern TenantUserDetailView.vue's role-catalog panels use.
const scopesTotalPages = ref(0)
const isScopesLoading = ref(true)
const scopesError = ref<string | null>(null)
const scopesErrorRetryable = ref(true)
const scopeSearchQuery = ref('')

async function loadScopes(): Promise<void> {
  isScopesLoading.value = true
  scopesError.value = null
  const result = await listScopes(slug, rsId, 0, SCOPE_CATALOG_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      scopes.value = result.value.items
      scopesTotalPages.value = result.value.totalPages
      isScopesLoading.value = false
      break
    case 'reauth':
      break
    default: {
      scopes.value = []
      const props = errorStateProps(result)
      scopesError.value = props.message
      scopesErrorRetryable.value = props.retryable
      isScopesLoading.value = false
      break
    }
  }
}

onMounted(loadScopes)

const scopesDataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isScopesLoading.value) return 'loading'
  if (scopesError.value) return 'error'
  return 'loaded'
})

const hasActiveScopeFilter = computed(() => scopeSearchQuery.value.trim().length > 0)

const filteredScopes = computed<ScopeView[]>(() => {
  const q = scopeSearchQuery.value.trim().toLowerCase()
  if (!q) return scopes.value
  return scopes.value.filter(
    (s) => s.scopeName.toLowerCase().includes(q) || (s.description ?? '').toLowerCase().includes(q),
  )
})

const scopeColumns = computed<ColumnDef<ScopeView, unknown>[]>(() => [
  {
    id: 'scope',
    header: 'Scope',
    // scopeName is stored bare — the slug: prefix is applied at token
    // issuance, not stored (ResourceServerScope's own doc comment). Spec
    // §6.4.4 wants the full prefixed form displayed regardless.
    cell: ({ row }) =>
      h(
        'code',
        { class: 'font-mono text-xs' },
        `${rs.value?.slug ?? ''}:${row.original.scopeName}`,
      ),
  },
  {
    id: 'description',
    header: 'Description',
    cell: ({ row }) => row.original.description || '—',
  },
  {
    id: 'default',
    header: 'Default',
    cell: ({ row }) => (row.original.isDefault ? 'Yes' : 'No'),
  },
  {
    id: 'requiresConsent',
    header: 'Requires consent',
    cell: ({ row }) => (row.original.requiresConsent ? 'Yes' : 'No'),
  },
  {
    id: 'usedBy',
    header: 'Used by',
    cell: ({ row }) => {
      const count = row.original.usedByRoleCount
      if (count === null || count === undefined) return '—'
      return `${count} role${count === 1 ? '' : 's'}`
    },
  },
  {
    id: 'actions',
    header: 'Actions',
    cell: ({ row }): VNode => {
      const scope = row.original
      return h('div', { class: 'flex items-center gap-2' }, [
        h(
          Button,
          {
            id: `scope-edit-${scope.id}`,
            variant: 'outline',
            size: 'sm',
            onClick: () => openEditScope(scope),
          },
          { default: () => 'Edit' },
        ),
        h(
          Button,
          {
            id: `scope-delete-${scope.id}`,
            variant: 'destructive',
            size: 'sm',
            onClick: () => (scopePendingDelete.value = scope),
          },
          { default: () => 'Delete' },
        ),
      ])
    },
  },
])

// ---- add/edit scope dialog --------------------------------------------

type ScopeDialogMode = { kind: 'add' } | { kind: 'edit'; scopeId: string }

const scopeDialog = ref<ScopeDialogMode | null>(null)
const scopeForm = reactive({
  scopeName: '',
  description: '',
  isDefault: false,
  requiresConsent: false,
})
const scopeErrors = reactive<Record<string, string>>({})
const scopeBanner = ref('')
const isScopeSaving = ref(false)

function openAddScope(): void {
  scopeForm.scopeName = ''
  scopeForm.description = ''
  scopeForm.isDefault = false
  scopeForm.requiresConsent = false
  scopeBanner.value = ''
  for (const key of Object.keys(scopeErrors)) delete scopeErrors[key]
  scopeDialog.value = { kind: 'add' }
}

function openEditScope(scope: ScopeView): void {
  scopeForm.scopeName = scope.scopeName
  scopeForm.description = scope.description ?? ''
  scopeForm.isDefault = scope.isDefault
  scopeForm.requiresConsent = scope.requiresConsent
  scopeBanner.value = ''
  for (const key of Object.keys(scopeErrors)) delete scopeErrors[key]
  scopeDialog.value = { kind: 'edit', scopeId: scope.id }
}

function closeScopeDialog(open: boolean): void {
  if (!open) scopeDialog.value = null
}

async function handleScopeSubmit(): Promise<void> {
  if (isScopeSaving.value || !scopeDialog.value) return
  scopeBanner.value = ''
  for (const key of Object.keys(scopeErrors)) delete scopeErrors[key]

  isScopeSaving.value = true
  try {
    const result =
      scopeDialog.value.kind === 'add'
        ? await addScope(slug, rsId, {
            scopeName: scopeForm.scopeName,
            description: scopeForm.description || undefined,
            isDefault: scopeForm.isDefault,
            requiresConsent: scopeForm.requiresConsent,
          })
        : // Full replacement, always all three — see the file header comment.
          await updateScope(slug, rsId, scopeDialog.value.scopeId, {
            description: scopeForm.description || undefined,
            isDefault: scopeForm.isDefault,
            requiresConsent: scopeForm.requiresConsent,
          })

    switch (result.kind) {
      case 'ok':
        scopeDialog.value = null
        await loadScopes()
        break
      case 'validationErrors':
        Object.assign(scopeErrors, result.errors)
        break
      case 'conflict': {
        const field = RESOURCE_SERVER_CONFLICT_FIELDS[result.code]
        if (field) scopeErrors[field] = result.message
        else scopeBanner.value = result.message
        break
      }
      case 'reauth':
        break
      default:
        scopeBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isScopeSaving.value = false
  }
}

// ---- delete scope -------------------------------------------------

const scopePendingDelete = ref<ScopeView | null>(null)
const isScopeDeleting = ref(false)
const scopeDeleteError = ref('')

async function confirmScopeDelete(): Promise<void> {
  if (isScopeDeleting.value || !scopePendingDelete.value) return
  scopeDeleteError.value = ''
  isScopeDeleting.value = true
  try {
    const result = await deleteScope(slug, rsId, scopePendingDelete.value.id)
    switch (result.kind) {
      case 'ok':
        scopePendingDelete.value = null
        await loadScopes()
        break
      case 'reauth':
        break
      default:
        scopeDeleteError.value = describeAdminError(result)
        break
    }
  } finally {
    isScopeDeleting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToList"
      >&larr; Back to resource servers</Button
    >

    <QueryState
      :loading="isLoading"
      :error="errorMessage"
      :retryable="errorRetryable"
      @retry="loadRS"
    >
      <div v-if="rs" class="flex flex-col gap-6">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <h1 id="rs-detail-name" class="text-xl font-semibold tracking-tight">
                {{ rs.name }}
              </h1>
              <p class="text-sm text-muted-foreground font-mono">{{ rs.slug }}</p>
            </div>
            <Badge :variant="rs.autoCreated ? 'secondary' : 'outline'">
              {{ rs.autoCreated ? 'Created with a client' : 'Standalone' }}
            </Badge>
          </div>

          <div class="flex flex-col gap-1.5">
            <Label>Audience identifier</Label>
            <p class="text-xs text-muted-foreground">
              Placed in issued tokens' <code class="font-mono">aud</code> claim. Immutable after
              creation — there is no way to change it here, by design.
            </p>
            <code
              id="rs-audience"
              class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all"
            >
              {{ rs.audienceIdentifier }}
            </code>
          </div>

          <form
            id="rs-edit-form"
            class="flex flex-col gap-4 border-t border-border pt-4"
            novalidate
            @submit.prevent="handleSaveEdit"
          >
            <FormField id="rs-edit-name" label="Name" :error="editErrors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="rs-edit-name"
                  v-model="editForm.name"
                  type="text"
                  required
                  :disabled="isSaving"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <FormField id="rs-edit-slug" label="Slug" :error="editErrors.slug">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="rs-edit-slug"
                  v-model="editForm.slug"
                  type="text"
                  required
                  :disabled="isSaving"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <p v-if="editBanner" role="alert" class="text-sm text-destructive">{{ editBanner }}</p>
            <Button
              id="rs-save-edit"
              type="submit"
              variant="outline"
              class="self-start"
              :disabled="isSaving"
            >
              {{ isSaving ? 'Saving…' : 'Save changes' }}
            </Button>
          </form>

          <div class="border-t border-border pt-4">
            <Button
              v-if="actions.includes('delete')"
              id="rs-delete-button"
              variant="destructive"
              @click="isDeleteConfirmOpen = true"
            >
              Delete resource server
            </Button>
            <p v-else class="text-sm text-muted-foreground">
              This resource server was created automatically with its client and cannot be deleted
              directly — delete the client instead.
            </p>
            <p v-if="deleteError" role="alert" class="text-sm text-destructive mt-2">
              {{ deleteError }}
            </p>
          </div>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-semibold tracking-tight">Scopes</h2>
            <Button id="new-scope-button" size="sm" @click="openAddScope">Add scope</Button>
          </div>
          <p class="text-xs text-muted-foreground">
            "Used by" counts application roles bundling each scope. Client usage isn't tracked yet.
          </p>
          <p
            v-if="scopesTotalPages > 1"
            id="scopes-truncated-notice"
            class="text-xs text-muted-foreground"
          >
            This resource server has more than {{ scopes.length }} scopes; only the first
            {{ scopes.length }} are shown here.
          </p>

          <DataTable
            :columns="scopeColumns"
            :data="filteredScopes"
            :row-key="(s: ScopeView) => s.id"
            :state="scopesDataTableState"
            :row-attrs="(s: ScopeView) => ({ 'data-scope-id': s.id })"
            :page="0"
            :size="SCOPE_CATALOG_PAGE_SIZE"
            :total-elements="scopes.length"
            :total-pages="1"
            :error-message="scopesError ?? undefined"
            :error-retryable="scopesErrorRetryable"
            :has-active-filters="hasActiveScopeFilter"
            empty-title="No scopes defined yet."
            empty-description="Add one to get started."
            filtered-empty-title="No scopes match your search."
            filtered-empty-description="Try a different name or description."
            search-placeholder="Search scopes…"
            @update:search="(q: string) => (scopeSearchQuery = q)"
            @retry="loadScopes"
          />
          <p v-if="scopeDeleteError" role="alert" class="text-sm text-destructive">
            {{ scopeDeleteError }}
          </p>
        </div>

        <div class="rounded-xl border border-border p-6">
          <ApplicationRolesPanel :slug="slug" :rs-id="rsId" />
        </div>
      </div>
    </QueryState>

    <Dialog :open="scopeDialog !== null" @update:open="closeScopeDialog">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{
            scopeDialog?.kind === 'add' ? 'Add a scope' : 'Edit scope'
          }}</DialogTitle>
          <DialogDescription>
            {{
              scopeDialog?.kind === 'add'
                ? 'The scope name cannot be changed after creation.'
                : 'The scope name is immutable and shown here read-only.'
            }}
          </DialogDescription>
        </DialogHeader>
        <form
          id="scope-form"
          class="flex flex-col gap-4"
          novalidate
          @submit.prevent="handleScopeSubmit"
        >
          <FormField
            v-if="scopeDialog?.kind === 'add'"
            id="scope-name"
            label="Scope name"
            :error="scopeErrors.scopeName"
          >
            <template #default="{ hasError, describedBy }">
              <Input
                id="scope-name"
                v-model="scopeForm.scopeName"
                type="text"
                required
                :disabled="isScopeSaving"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>
          <div v-else class="flex flex-col gap-1.5">
            <Label>Scope name</Label>
            <code
              id="scope-name-readonly"
              class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono"
            >
              {{ scopeForm.scopeName }}
            </code>
          </div>

          <FormField id="scope-description" label="Description" :error="scopeErrors.description">
            <template #default="{ hasError, describedBy }">
              <Input
                id="scope-description"
                v-model="scopeForm.description"
                type="text"
                :disabled="isScopeSaving"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <div class="flex items-center gap-2">
            <Checkbox
              id="scope-is-default"
              :model-value="scopeForm.isDefault"
              :disabled="isScopeSaving"
              @update:model-value="(v) => (scopeForm.isDefault = Boolean(v))"
            />
            <Label for="scope-is-default" class="font-normal"
              >Default (auto-granted when a client accesses this resource server)</Label
            >
          </div>
          <div class="flex items-center gap-2">
            <Checkbox
              id="scope-requires-consent"
              :model-value="scopeForm.requiresConsent"
              :disabled="isScopeSaving"
              @update:model-value="(v) => (scopeForm.requiresConsent = Boolean(v))"
            />
            <Label for="scope-requires-consent" class="font-normal">Requires consent</Label>
          </div>

          <p v-if="scopeBanner" role="alert" class="text-sm text-destructive">{{ scopeBanner }}</p>

          <DialogFooter>
            <Button id="scope-form-submit" type="submit" :disabled="isScopeSaving">
              {{
                isScopeSaving
                  ? 'Saving…'
                  : scopeDialog?.kind === 'add'
                    ? 'Add scope'
                    : 'Save changes'
              }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <ConfirmDialog
      :open="isDeleteConfirmOpen"
      title="Delete this resource server?"
      description="This is permanent. Its scope catalog and any client authorizations against it are removed with it."
      confirm-label="Delete"
      :pending="isDeleting"
      @update:open="(open) => (isDeleteConfirmOpen = open)"
      @confirm="confirmDelete"
    />

    <ConfirmDialog
      :open="scopePendingDelete !== null"
      title="Delete this scope?"
      :description="`This removes '${scopePendingDelete?.scopeName}' from the catalog. Clients currently requesting it will no longer be able to.`"
      confirm-label="Delete"
      :pending="isScopeDeleting"
      @update:open="
        (open) => {
          if (!open) scopePendingDelete = null
        }
      "
      @confirm="confirmScopeDelete"
    />
  </div>
</template>
