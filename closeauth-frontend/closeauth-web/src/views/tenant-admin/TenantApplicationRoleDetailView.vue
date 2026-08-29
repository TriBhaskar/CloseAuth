<script setup lang="ts">
// Stage UI-3d: application-role detail — read-only name, an inline edit form
// for description/isDefault (full replacement, same shape as
// TenantRolesView.vue's edit dialog and TenantResourceServerDetailView.vue's
// scope edit), and the scope-bundle panel.
//
// The scope-bundle checkbox list is fed EXCLUSIVELY by
// listScopes(slug, rsId, ...) — this role's own resource server's scope
// catalog, from tenantAdminResourceServers.ts (UI-3c) — never any other RS's
// scopes. That is what makes application_role.scope_rs_mismatch structurally
// unreachable from this UI: the picker cannot construct a cross-RS request in
// the first place, so the "never offer an action the backend must refuse"
// rule is satisfied by the picker's data source, not by validation. The
// defensive branch below (on 400 + that exact code) exists only in case a
// scope/role goes stale between fetch and toggle — it renders a written
// explanation, never result.errors verbatim (those keys are RS UUIDs, not
// scope names).
//
// Toggling issues one POST/DELETE per scope (no bulk-set endpoint exists).
// FE-4b: pending state is now PER-SCOPE (pendingScopeIds, a Set), replacing
// the old single scopeActionPending flag that disabled every checkbox
// while any one was in flight — §7.5: "mutations disable their own trigger
// only, never the whole form." Same fix FE-4a applied to
// TenantUserDetailView.vue's role toggles.
//
// FE-4b also adds an Assignees section (spec §6.4.5) — everyone currently
// holding this application role, via the new
// ApplicationRoleController.assignees endpoint.
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import QueryState from '@/components/admin/QueryState.vue'
import FormField from '@/components/common/FormField.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import {
  addScopeToRole,
  getApplicationRole,
  getApplicationRoleAssignees,
  listRoleScopes,
  removeScopeFromRole,
  updateApplicationRole,
  APPLICATION_ROLE_CONFLICT_FIELDS,
  DEFAULT_ROLE_SCOPES_PAGE_SIZE,
  type ApplicationRoleView,
} from '@/api/tenantAdminApplicationRoles'
import { listScopes, type ScopeView } from '@/api/tenantAdminResourceServers'
import type { RoleAssigneeView } from '@/api/tenantAdminRoles'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const rsId = String(route.params.rsId ?? '')
const roleId = String(route.params.roleId ?? '')

// ---- role ------------------------------------------------------------

const role = ref<ApplicationRoleView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)

async function loadRole(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getApplicationRole(slug, rsId, roleId)
  switch (result.kind) {
    case 'ok':
      role.value = result.value
      resetEditForm(result.value)
      isLoading.value = false
      break
    case 'reauth':
      break
    default: {
      role.value = null
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

// ---- assignees (new, FE-4b) -------------------------------------------

const assignees = ref<RoleAssigneeView[]>([])
const isAssigneesLoading = ref(true)
const assigneesError = ref<string | null>(null)
const assigneesErrorRetryable = ref(true)

async function loadAssignees(): Promise<void> {
  isAssigneesLoading.value = true
  assigneesError.value = null
  const result = await getApplicationRoleAssignees(slug, rsId, roleId)
  switch (result.kind) {
    case 'ok':
      assignees.value = result.value
      isAssigneesLoading.value = false
      break
    case 'reauth':
      break
    default: {
      assignees.value = []
      const props = errorStateProps(result)
      assigneesError.value = props.message
      assigneesErrorRetryable.value = props.retryable
      isAssigneesLoading.value = false
      break
    }
  }
}

function assigneeName(a: RoleAssigneeView): string {
  return [a.firstName, a.lastName].filter(Boolean).join(' ') || '—'
}

onMounted(() => {
  void loadRole()
  void loadAssignees()
})

function backToResourceServer(): void {
  void router.push({ name: 'tenant-admin-resource-server-detail', params: { slug, rsId } })
}

// ---- edit description/isDefault -----------------------------------------

const editForm = reactive({ description: '', isDefault: false })
const editErrors = reactive<Record<string, string>>({})
const editBanner = ref('')
const isSaving = ref(false)

function resetEditForm(value: ApplicationRoleView): void {
  editForm.description = value.description ?? ''
  editForm.isDefault = value.isDefault
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]
}

async function handleSaveEdit(): Promise<void> {
  if (isSaving.value || !role.value) return
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]

  isSaving.value = true
  try {
    // Full replacement — always both fields, pre-populated.
    const result = await updateApplicationRole(slug, rsId, roleId, {
      description: editForm.description || undefined,
      isDefault: editForm.isDefault,
    })
    switch (result.kind) {
      case 'ok':
        role.value = result.value
        break
      case 'validationErrors':
        Object.assign(editErrors, result.errors)
        break
      case 'conflict': {
        const field = APPLICATION_ROLE_CONFLICT_FIELDS[result.code]
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

// ---- scope bundle -----------------------------------------------------

const rsScopes = ref<ScopeView[]>([])
const bundledScopeIds = ref<Set<string>>(new Set())
const isScopesLoading = ref(true)
const scopesError = ref<string | null>(null)
const scopesErrorRetryable = ref(true)
const scopesTruncated = ref(false)
// FE-4b: per-scope pending (§7.5) — replaces the old panel-wide boolean.
const pendingScopeIds = ref<Set<string>>(new Set())
const scopeActionError = ref('')

async function loadScopeBundle(): Promise<void> {
  isScopesLoading.value = true
  scopesError.value = null

  const [catalogResult, bundleResult] = await Promise.all([
    listScopes(slug, rsId, 0, DEFAULT_ROLE_SCOPES_PAGE_SIZE),
    listRoleScopes(slug, rsId, roleId, DEFAULT_ROLE_SCOPES_PAGE_SIZE),
  ])

  if (catalogResult.kind === 'reauth' || bundleResult.kind === 'reauth') return
  if (catalogResult.kind !== 'ok') {
    const props = errorStateProps(catalogResult)
    scopesError.value = props.message
    scopesErrorRetryable.value = props.retryable
    isScopesLoading.value = false
    return
  }
  if (bundleResult.kind !== 'ok') {
    const props = errorStateProps(bundleResult)
    scopesError.value = props.message
    scopesErrorRetryable.value = props.retryable
    isScopesLoading.value = false
    return
  }

  rsScopes.value = catalogResult.value.items
  scopesTruncated.value = catalogResult.value.totalPages > 1
  bundledScopeIds.value = new Set(bundleResult.value.items.map((s) => s.id))
  isScopesLoading.value = false
}

onMounted(loadScopeBundle)

const scopeRows = computed(() =>
  rsScopes.value.map((scope) => ({ scope, bundled: bundledScopeIds.value.has(scope.id) })),
)

async function toggleScope(scope: ScopeView, currentlyBundled: boolean): Promise<void> {
  if (pendingScopeIds.value.has(scope.id)) return
  scopeActionError.value = ''
  pendingScopeIds.value = new Set(pendingScopeIds.value).add(scope.id)
  try {
    const result = currentlyBundled
      ? await removeScopeFromRole(slug, rsId, roleId, scope.id)
      : await addScopeToRole(slug, rsId, roleId, scope.id)
    switch (result.kind) {
      case 'ok': {
        const bundleResult = await listRoleScopes(slug, rsId, roleId, DEFAULT_ROLE_SCOPES_PAGE_SIZE)
        if (bundleResult.kind === 'ok') {
          bundledScopeIds.value = new Set(bundleResult.value.items.map((s) => s.id))
        }
        break
      }
      case 'validationErrors':
        // application_role.scope_rs_mismatch, defensively: the picker only
        // ever offers this role's own RS's scopes, so this should be
        // unreachable — but if it ever fires, explain it in words, never
        // render result.errors (its keys are RS UUIDs, not scope names).
        scopeActionError.value =
          result.code === 'application_role.scope_rs_mismatch'
            ? 'This scope belongs to a different resource server and cannot be bundled into this role.'
            : describeAdminError(result)
        break
      case 'reauth':
        break
      default:
        scopeActionError.value = describeAdminError(result)
        break
    }
  } finally {
    const next = new Set(pendingScopeIds.value)
    next.delete(scope.id)
    pendingScopeIds.value = next
  }
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToResourceServer"
      >&larr; Back to resource server</Button
    >

    <QueryState
      :loading="isLoading"
      :error="errorMessage"
      :retryable="errorRetryable"
      @retry="loadRole"
    >
      <div v-if="role" class="flex flex-col gap-6">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex flex-col gap-1.5">
            <Label>Name</Label>
            <code
              id="app-role-name-readonly"
              class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono"
            >
              {{ role.name }}
            </code>
          </div>

          <form
            id="app-role-edit-form"
            class="flex flex-col gap-4 border-t border-border pt-4"
            novalidate
            @submit.prevent="handleSaveEdit"
          >
            <FormField
              id="app-role-edit-description"
              label="Description"
              :error="editErrors.description"
            >
              <template #default="{ hasError, describedBy }">
                <Input
                  id="app-role-edit-description"
                  v-model="editForm.description"
                  type="text"
                  :disabled="isSaving"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <div class="flex items-center gap-2">
              <Checkbox
                id="app-role-edit-is-default"
                :model-value="editForm.isDefault"
                :disabled="isSaving"
                @update:model-value="(v) => (editForm.isDefault = Boolean(v))"
              />
              <Label for="app-role-edit-is-default" class="font-normal"
                >Default (auto-granted to new users)</Label
              >
            </div>

            <p v-if="editBanner" role="alert" class="text-sm text-destructive">{{ editBanner }}</p>

            <Button
              id="app-role-save-edit"
              type="submit"
              variant="outline"
              class="self-start"
              :disabled="isSaving"
            >
              {{ isSaving ? 'Saving…' : 'Save changes' }}
            </Button>
          </form>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Scope bundle</h2>
          <p class="text-sm text-muted-foreground">
            Scopes from this role's own resource server only. Toggling calls the backend immediately
            — there is no separate save step.
          </p>

          <QueryState
            :loading="isScopesLoading"
            :error="scopesError"
            :retryable="scopesErrorRetryable"
            @retry="loadScopeBundle"
          >
            <div class="flex flex-col gap-3">
              <p v-if="scopesTruncated" role="alert" class="text-sm text-muted-foreground">
                This resource server has more than {{ rsScopes.length }} scopes; only the first
                {{ rsScopes.length }} are shown here.
              </p>

              <div
                v-for="{ scope, bundled } in scopeRows"
                :key="scope.id"
                class="flex items-center gap-2"
              >
                <Checkbox
                  :id="`app-role-scope-${scope.id}`"
                  :model-value="bundled"
                  :disabled="pendingScopeIds.has(scope.id)"
                  @update:model-value="() => toggleScope(scope, bundled)"
                />
                <Label :for="`app-role-scope-${scope.id}`" class="font-mono text-sm">{{
                  scope.scopeName
                }}</Label>
              </div>

              <p v-if="rsScopes.length === 0" class="text-sm text-muted-foreground">
                This resource server has no scopes defined yet.
              </p>

              <p v-if="scopeActionError" role="alert" class="text-sm text-destructive">
                {{ scopeActionError }}
              </p>
            </div>
          </QueryState>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Assignees</h2>
          <QueryState
            :loading="isAssigneesLoading"
            :error="assigneesError"
            :retryable="assigneesErrorRetryable"
            @retry="loadAssignees"
          >
            <p v-if="assignees.length === 0" class="text-sm text-muted-foreground">
              No one holds this role yet.
            </p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="a in assignees"
                :key="a.userId"
                :data-assignee-id="a.userId"
                class="flex flex-col gap-0.5 rounded-md border border-line p-2 text-sm"
              >
                <span>{{ a.email }}</span>
                <span class="text-xs text-muted-foreground"
                  >{{ assigneeName(a) }} · {{ a.status }}</span
                >
              </li>
            </ul>
          </QueryState>
        </div>
      </div>
    </QueryState>
  </div>
</template>
