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
// below the scopes panel — this view's own script/template are otherwise
// unchanged from UI-3c.
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import ApplicationRolesPanel from '@/components/admin/ApplicationRolesPanel.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
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
  DEFAULT_PAGE_SIZE,
  type ResourceServerView,
  type ScopeView,
} from '@/api/tenantAdminResourceServers'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const rsId = String(route.params.rsId ?? '')

// ---- resource server -------------------------------------------------

const rs = ref<ResourceServerView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

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
    default:
      rs.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
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
    const result = await updateResourceServer(slug, rsId, { name: editForm.name, slug: editForm.slug })
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

const scopePage = ref(0)
const scopeData = ref<PageView<ScopeView> | null>(null)
const isScopesLoading = ref(true)
const scopesError = ref<string | null>(null)

async function loadScopes(): Promise<void> {
  isScopesLoading.value = true
  scopesError.value = null
  const result = await listScopes(slug, rsId, scopePage.value, DEFAULT_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      scopeData.value = result.value
      isScopesLoading.value = false
      break
    case 'reauth':
      break
    default:
      scopeData.value = null
      scopesError.value = describeAdminError(result)
      isScopesLoading.value = false
      break
  }
}

onMounted(loadScopes)
watch(scopePage, loadScopes)

// ---- add/edit scope dialog --------------------------------------------

type ScopeDialogMode = { kind: 'add' } | { kind: 'edit'; scopeId: string }

const scopeDialog = ref<ScopeDialogMode | null>(null)
const scopeForm = reactive({ scopeName: '', description: '', isDefault: false, requiresConsent: false })
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
    <Button variant="ghost" size="sm" class="self-start" @click="backToList">&larr; Back to resource servers</Button>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div v-if="rs" class="flex flex-col gap-6">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <h1 id="rs-detail-name" class="text-xl font-semibold tracking-tight">{{ rs.name }}</h1>
              <p class="text-sm text-muted-foreground font-mono">{{ rs.slug }}</p>
            </div>
            <Badge :variant="rs.autoCreated ? 'secondary' : 'outline'">
              {{ rs.autoCreated ? 'Created with a client' : 'Standalone' }}
            </Badge>
          </div>

          <div class="flex flex-col gap-1.5">
            <Label>Audience identifier</Label>
            <p class="text-xs text-muted-foreground">
              Placed in issued tokens' <code class="font-mono">aud</code> claim. Immutable after creation — there is
              no way to change it here, by design.
            </p>
            <code id="rs-audience" class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all">
              {{ rs.audienceIdentifier }}
            </code>
          </div>

          <form id="rs-edit-form" class="flex flex-col gap-4 border-t border-border pt-4" novalidate @submit.prevent="handleSaveEdit">
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
            <Button id="rs-save-edit" type="submit" variant="outline" class="self-start" :disabled="isSaving">
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
              This resource server was created automatically with its client and cannot be deleted directly — delete
              the client instead.
            </p>
            <p v-if="deleteError" role="alert" class="text-sm text-destructive mt-2">{{ deleteError }}</p>
          </div>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-semibold tracking-tight">Scopes</h2>
            <Button id="new-scope-button" size="sm" @click="openAddScope">Add scope</Button>
          </div>

          <QueryState :loading="isScopesLoading" :error="scopesError">
            <div class="flex flex-col gap-4">
              <div class="rounded-lg border border-border overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Scope</TableHead>
                      <TableHead>Description</TableHead>
                      <TableHead>Default</TableHead>
                      <TableHead>Requires consent</TableHead>
                      <TableHead>Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    <TableEmpty v-if="scopeData && scopeData.items.length === 0" :colspan="5">
                      No scopes defined yet.
                    </TableEmpty>
                    <TableRow v-for="scope in scopeData?.items ?? []" :key="scope.id" :data-scope-id="scope.id">
                      <TableCell class="font-mono text-xs">{{ scope.scopeName }}</TableCell>
                      <TableCell>{{ scope.description || '—' }}</TableCell>
                      <TableCell>{{ scope.isDefault ? 'Yes' : 'No' }}</TableCell>
                      <TableCell>{{ scope.requiresConsent ? 'Yes' : 'No' }}</TableCell>
                      <TableCell>
                        <div class="flex items-center gap-2">
                          <Button :id="`scope-edit-${scope.id}`" variant="outline" size="sm" @click="openEditScope(scope)">Edit</Button>
                          <Button :id="`scope-delete-${scope.id}`" variant="destructive" size="sm" @click="scopePendingDelete = scope">Delete</Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </div>

              <AdminPagination
                v-if="scopeData"
                :page="scopeData.page"
                :size="scopeData.size"
                :total-elements="scopeData.totalElements"
                :total-pages="scopeData.totalPages"
                @update:page="(p) => (scopePage = p)"
              />
              <p v-if="scopeDeleteError" role="alert" class="text-sm text-destructive">{{ scopeDeleteError }}</p>
            </div>
          </QueryState>
        </div>

        <div class="rounded-xl border border-border p-6">
          <ApplicationRolesPanel :slug="slug" :rs-id="rsId" />
        </div>
      </div>
    </QueryState>

    <Dialog :open="scopeDialog !== null" @update:open="closeScopeDialog">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{ scopeDialog?.kind === 'add' ? 'Add a scope' : 'Edit scope' }}</DialogTitle>
          <DialogDescription>
            {{
              scopeDialog?.kind === 'add'
                ? 'The scope name cannot be changed after creation.'
                : 'The scope name is immutable and shown here read-only.'
            }}
          </DialogDescription>
        </DialogHeader>
        <form id="scope-form" class="flex flex-col gap-4" novalidate @submit.prevent="handleScopeSubmit">
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
            <code id="scope-name-readonly" class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono">
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
            <Label for="scope-is-default" class="font-normal">Default (auto-granted when a client accesses this resource server)</Label>
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
              {{ isScopeSaving ? 'Saving…' : scopeDialog?.kind === 'add' ? 'Add scope' : 'Save changes' }}
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
      @update:open="(open) => { if (!open) scopePendingDelete = null }"
      @confirm="confirmScopeDelete"
    />
  </div>
</template>
