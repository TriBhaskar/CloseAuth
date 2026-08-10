<script setup lang="ts">
// Stage UI-3b: user detail — lifecycle actions and tenant-role assignment.
// Lifecycle buttons are generated strictly from availableActions(status)
// (identity/service/UserStateMachine.java's real transition matrix), so the
// UI never offers an action the backend would refuse with a 409. Suspend
// and delete go through ConfirmDialog with copy naming their REAL
// consequence (immediate token revocation; for delete, also that it's
// terminal) — approve/activate have no such consequence and run directly.
//
// The roles panel is this stage's most deliberate honesty exercise: a role
// name GET /tenant-roles returns that isn't in the fetched catalog (a
// truncated >100-role catalog, or a concurrent role delete/rename) is
// rendered checked, disabled, and explained — never silently dropped, which
// would render a user as NOT holding a role they actually hold.
//
// Stage UI-3d adds an "Application roles" panel below the tenant-roles one.
// Unlike tenant roles, application-role names are unique only per resource
// server (uq_application_roles_rs_name), so there is no tenant-wide "all
// application roles this user holds" read — getHeldApplicationRoleNames is
// deliberately RS-scoped. The panel is therefore an RS selector (fetched at
// size=100, same truncation-notice rule as the tenant-role catalog) that,
// once a resource server is picked, loads that RS's role catalog + the
// user's held names there and joins them by name — unambiguous because
// names are unique per RS. No last-admin-style guard exists at this tier.
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import QueryState from '@/components/admin/QueryState.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import UserStatusBadge from '@/components/admin/UserStatusBadge.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  activateUser,
  approveUser,
  availableActions,
  deleteUser,
  getUser,
  suspendUser,
  type UserLifecycleAction,
  type UserView,
} from '@/api/tenantAdminUsers'
import { assignRole, getHeldRoleNames, joinHeldRoles, listRoles, revokeRole, type TenantRoleView } from '@/api/tenantAdminRoles'
import { listResourceServers, type ResourceServerView } from '@/api/tenantAdminResourceServers'
import {
  assignApplicationRole,
  getHeldApplicationRoleNames,
  joinHeldApplicationRoles,
  listApplicationRoles,
  revokeApplicationRole,
  DEFAULT_APPLICATION_ROLE_PAGE_SIZE,
  type ApplicationRoleView,
} from '@/api/tenantAdminApplicationRoles'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const userId = String(route.params.userId ?? '')

// ---- user ----------------------------------------------------------------

const user = ref<UserView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function loadUser(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getUser(slug, userId)
  switch (result.kind) {
    case 'ok':
      user.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      user.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

const actionPending = ref(false)
const actionError = ref('')
const confirmAction = ref<'suspend' | 'delete' | null>(null)

const lifecycleActions = computed<UserLifecycleAction[]>(() => (user.value ? availableActions(user.value.status) : []))

function startAction(action: UserLifecycleAction): void {
  if (actionPending.value) return
  if (action === 'suspend' || action === 'delete') {
    confirmAction.value = action
    return
  }
  void runLifecycle(action)
}

async function runLifecycle(action: UserLifecycleAction): Promise<void> {
  if (actionPending.value) return
  actionError.value = ''
  actionPending.value = true
  try {
    const result =
      action === 'approve'
        ? await approveUser(slug, userId)
        : action === 'activate'
          ? await activateUser(slug, userId)
          : action === 'suspend'
            ? await suspendUser(slug, userId)
            : await deleteUser(slug, userId)

    switch (result.kind) {
      case 'ok':
        user.value = result.value
        confirmAction.value = null
        break
      case 'conflict':
        actionError.value =
          result.code === 'tenant_role.last_admin'
            ? "This user is the tenant's last active administrator. Assign TENANT_ADMIN to another active user before removing this one."
            : result.message
        break
      case 'reauth':
        break
      default:
        actionError.value = describeAdminError(result)
        break
    }
  } finally {
    actionPending.value = false
  }
}

const confirmTitle = computed(() => (confirmAction.value === 'suspend' ? 'Suspend this user?' : 'Delete this user?'))
const confirmDescription = computed(() =>
  confirmAction.value === 'suspend'
    ? "This immediately revokes the user's live access tokens — any signed-in session stops working within seconds, not at token expiry."
    : "This is permanent: a deleted user has no path back to any other status. Their live access tokens are revoked immediately.",
)

// ---- tenant roles ----------------------------------------------------

const roleCatalog = ref<TenantRoleView[]>([])
const heldNames = ref<string[]>([])
const isRolesLoading = ref(true)
const rolesError = ref<string | null>(null)
const catalogTruncated = ref(false)
const roleActionPending = ref(false)
const rolesActionError = ref('')

async function loadRoles(): Promise<void> {
  isRolesLoading.value = true
  rolesError.value = null

  const [catalogResult, heldResult] = await Promise.all([listRoles(slug), getHeldRoleNames(slug, userId)])

  if (catalogResult.kind === 'reauth' || heldResult.kind === 'reauth') {
    return
  }
  if (catalogResult.kind !== 'ok') {
    rolesError.value = describeAdminError(catalogResult)
    isRolesLoading.value = false
    return
  }
  if (heldResult.kind !== 'ok') {
    rolesError.value = describeAdminError(heldResult)
    isRolesLoading.value = false
    return
  }

  roleCatalog.value = catalogResult.value.items
  catalogTruncated.value = catalogResult.value.totalPages > 1
  heldNames.value = heldResult.value
  isRolesLoading.value = false
}

async function refreshHeldRoles(): Promise<void> {
  const result = await getHeldRoleNames(slug, userId)
  if (result.kind === 'ok') heldNames.value = result.value
}

const heldRoles = computed(() => joinHeldRoles(heldNames.value, roleCatalog.value))
const heldNameSet = computed(() => new Set(heldRoles.value.filter((r) => r.kind === 'joined').map((r) => r.name)))
const catalogRows = computed(() => roleCatalog.value.map((role) => ({ role, held: heldNameSet.value.has(role.name) })))
const unresolvedHeld = computed(() => heldRoles.value.filter((r) => r.kind === 'unresolved'))

async function toggleRole(role: TenantRoleView, currentlyHeld: boolean): Promise<void> {
  if (roleActionPending.value) return
  rolesActionError.value = ''
  roleActionPending.value = true
  try {
    const result = currentlyHeld ? await revokeRole(slug, userId, role.id) : await assignRole(slug, userId, role.id)
    switch (result.kind) {
      case 'ok':
        await refreshHeldRoles()
        break
      case 'conflict':
        rolesActionError.value =
          result.code === 'tenant_role.last_admin'
            ? "This user is the tenant's last active administrator. Assign TENANT_ADMIN to another active user before revoking it here."
            : result.message
        break
      case 'reauth':
        break
      default:
        rolesActionError.value = describeAdminError(result)
        break
    }
  } finally {
    roleActionPending.value = false
  }
}

// ---- application roles -------------------------------------------------

const resourceServers = ref<ResourceServerView[]>([])
const isRSLoading = ref(true)
const rsError = ref<string | null>(null)
const rsTruncated = ref(false)
const selectedRsId = ref('')

async function loadResourceServers(): Promise<void> {
  isRSLoading.value = true
  rsError.value = null
  const result = await listResourceServers(slug, 0, 100)
  switch (result.kind) {
    case 'ok':
      resourceServers.value = result.value.items
      rsTruncated.value = result.value.totalPages > 1
      isRSLoading.value = false
      break
    case 'reauth':
      break
    default:
      resourceServers.value = []
      rsError.value = describeAdminError(result)
      isRSLoading.value = false
      break
  }
}

const appRoleCatalog = ref<ApplicationRoleView[]>([])
const appHeldNames = ref<string[]>([])
const isAppRolesLoading = ref(false)
const appRolesError = ref<string | null>(null)
const appCatalogTruncated = ref(false)
const appRoleActionPending = ref(false)
const appRolesActionError = ref('')

async function loadApplicationRoles(): Promise<void> {
  if (!selectedRsId.value) {
    appRoleCatalog.value = []
    appHeldNames.value = []
    return
  }
  isAppRolesLoading.value = true
  appRolesError.value = null

  const [catalogResult, heldResult] = await Promise.all([
    listApplicationRoles(slug, selectedRsId.value, 0, DEFAULT_APPLICATION_ROLE_PAGE_SIZE),
    getHeldApplicationRoleNames(slug, userId, selectedRsId.value),
  ])

  if (catalogResult.kind === 'reauth' || heldResult.kind === 'reauth') {
    return
  }
  if (catalogResult.kind !== 'ok') {
    appRolesError.value = describeAdminError(catalogResult)
    isAppRolesLoading.value = false
    return
  }
  if (heldResult.kind !== 'ok') {
    appRolesError.value = describeAdminError(heldResult)
    isAppRolesLoading.value = false
    return
  }

  appRoleCatalog.value = catalogResult.value.items
  appCatalogTruncated.value = catalogResult.value.totalPages > 1
  appHeldNames.value = heldResult.value
  isAppRolesLoading.value = false
}

async function refreshHeldApplicationRoles(): Promise<void> {
  if (!selectedRsId.value) return
  const result = await getHeldApplicationRoleNames(slug, userId, selectedRsId.value)
  if (result.kind === 'ok') appHeldNames.value = result.value
}

watch(selectedRsId, () => {
  appRolesActionError.value = ''
  void loadApplicationRoles()
})

const appHeldRoles = computed(() => joinHeldApplicationRoles(appHeldNames.value, appRoleCatalog.value))
const appHeldNameSet = computed(() => new Set(appHeldRoles.value.filter((r) => r.kind === 'joined').map((r) => r.name)))
const appCatalogRows = computed(() =>
  appRoleCatalog.value.map((role) => ({ role, held: appHeldNameSet.value.has(role.name) })),
)
const appUnresolvedHeld = computed(() => appHeldRoles.value.filter((r) => r.kind === 'unresolved'))

async function toggleApplicationRole(role: ApplicationRoleView, currentlyHeld: boolean): Promise<void> {
  if (appRoleActionPending.value) return
  appRolesActionError.value = ''
  appRoleActionPending.value = true
  try {
    const result = currentlyHeld
      ? await revokeApplicationRole(slug, userId, role.id)
      : await assignApplicationRole(slug, userId, role.id)
    switch (result.kind) {
      case 'ok':
        await refreshHeldApplicationRoles()
        break
      case 'reauth':
        break
      default:
        appRolesActionError.value = describeAdminError(result)
        break
    }
  } finally {
    appRoleActionPending.value = false
  }
}

onMounted(() => {
  void loadUser()
  void loadRoles()
  void loadResourceServers()
})

function backToList(): void {
  void router.push({ name: 'tenant-admin-users', params: { slug } })
}

const actionLabels: Record<UserLifecycleAction, string> = {
  approve: 'Approve',
  activate: 'Activate',
  suspend: 'Suspend',
  delete: 'Delete',
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToList">&larr; Back to users</Button>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div v-if="user" class="flex flex-col gap-6">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <h1 id="user-detail-email" class="text-xl font-semibold tracking-tight">{{ user.email }}</h1>
              <p class="text-sm text-muted-foreground">{{ [user.firstName, user.lastName].filter(Boolean).join(' ') || 'No name on file' }}</p>
            </div>
            <UserStatusBadge :status="user.status" />
          </div>

          <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt class="text-muted-foreground">Email verified</dt>
            <dd>{{ user.emailVerified ? 'Yes' : 'No' }}</dd>
            <dt class="text-muted-foreground">Phone</dt>
            <dd>{{ user.phone ?? '—' }}</dd>
            <dt class="text-muted-foreground">Created</dt>
            <dd>{{ new Date(user.createdAt).toLocaleString() }}</dd>
            <dt class="text-muted-foreground">Last login</dt>
            <dd>{{ user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'Never' }}</dd>
          </dl>

          <div class="flex items-center gap-2 flex-wrap">
            <Button
              v-for="action in lifecycleActions"
              :id="`user-action-${action}`"
              :key="action"
              :variant="action === 'delete' || action === 'suspend' ? 'destructive' : 'default'"
              :disabled="actionPending"
              @click="startAction(action)"
            >
              {{ actionLabels[action] }}
            </Button>
          </div>
          <p v-if="actionError" role="alert" class="text-sm text-destructive">{{ actionError }}</p>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Tenant roles</h2>

          <QueryState :loading="isRolesLoading" :error="rolesError">
            <div class="flex flex-col gap-3">
              <p v-if="catalogTruncated" role="alert" class="text-sm text-muted-foreground">
                This tenant has more than {{ roleCatalog.length }} roles; only the first {{ roleCatalog.length }} are shown here.
              </p>

              <div v-for="{ role, held } in catalogRows" :key="role.id" class="flex items-center gap-2">
                <Checkbox
                  :id="`role-${role.id}`"
                  :model-value="held"
                  :disabled="roleActionPending"
                  @update:model-value="() => toggleRole(role, held)"
                />
                <Label :for="`role-${role.id}`">{{ role.name }}</Label>
                <span v-if="role.isSystem" class="text-xs text-muted-foreground">(system)</span>
              </div>

              <div v-for="unresolved in unresolvedHeld" :key="unresolved.name" class="flex items-center gap-2">
                <Checkbox :model-value="true" disabled />
                <Label class="text-muted-foreground">{{ unresolved.name }}</Label>
                <span role="alert" class="text-xs text-destructive">
                  held — not in the role catalog, cannot be changed here
                </span>
              </div>

              <p v-if="rolesActionError" role="alert" class="text-sm text-destructive">{{ rolesActionError }}</p>
            </div>
          </QueryState>
        </div>

        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Application roles</h2>
          <p class="text-sm text-muted-foreground">
            Application roles are scoped to one resource server. Select one to see and change this user's roles
            there.
          </p>

          <QueryState :loading="isRSLoading" :error="rsError">
            <div class="flex flex-col gap-3">
              <p v-if="rsTruncated" role="alert" class="text-sm text-muted-foreground">
                This tenant has more than {{ resourceServers.length }} resource servers; only the first
                {{ resourceServers.length }} are shown here.
              </p>

              <div class="flex flex-col gap-1.5 max-w-xs">
                <Label for="app-role-rs-select">Resource server</Label>
                <select
                  id="app-role-rs-select"
                  v-model="selectedRsId"
                  class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                >
                  <option value="">Select a resource server…</option>
                  <option v-for="rs in resourceServers" :key="rs.id" :value="rs.id">{{ rs.name }}</option>
                </select>
              </div>

              <QueryState v-if="selectedRsId" :loading="isAppRolesLoading" :error="appRolesError">
                <div class="flex flex-col gap-3">
                  <p v-if="appCatalogTruncated" role="alert" class="text-sm text-muted-foreground">
                    This resource server has more than {{ appRoleCatalog.length }} roles; only the first
                    {{ appRoleCatalog.length }} are shown here.
                  </p>

                  <p v-if="appRoleCatalog.length === 0 && appUnresolvedHeld.length === 0" class="text-sm text-muted-foreground">
                    This resource server has no application roles defined yet.
                  </p>

                  <div v-for="{ role, held } in appCatalogRows" :key="role.id" class="flex items-center gap-2">
                    <Checkbox
                      :id="`app-role-${role.id}`"
                      :model-value="held"
                      :disabled="appRoleActionPending"
                      @update:model-value="() => toggleApplicationRole(role, held)"
                    />
                    <Label :for="`app-role-${role.id}`">{{ role.name }}</Label>
                  </div>

                  <div v-for="unresolved in appUnresolvedHeld" :key="unresolved.name" class="flex items-center gap-2">
                    <Checkbox :model-value="true" disabled />
                    <Label class="text-muted-foreground">{{ unresolved.name }}</Label>
                    <span role="alert" class="text-xs text-destructive">
                      held — not in this resource server's role catalog, cannot be changed here
                    </span>
                  </div>

                  <p v-if="appRolesActionError" role="alert" class="text-sm text-destructive">{{ appRolesActionError }}</p>
                </div>
              </QueryState>
            </div>
          </QueryState>
        </div>
      </div>
    </QueryState>

    <ConfirmDialog
      :open="confirmAction !== null"
      :title="confirmTitle"
      :description="confirmDescription"
      :confirm-label="confirmAction === 'suspend' ? 'Suspend' : 'Delete'"
      :pending="actionPending"
      @update:open="(open: boolean) => { if (!open) confirmAction = null }"
      @confirm="() => confirmAction && runLifecycle(confirmAction)"
    />
  </div>
</template>
