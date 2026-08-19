<script setup lang="ts">
// Stage UI-4 / FE-3c: the platform console's platform-admin management
// surface — list, a create dialog, suspend/activate (both confirmed, matching
// FE-3a's tenant-list precedent), and role assign/revoke.
//
// FE-3c decisions:
//  - Role assignment is a "Manage roles" dialog (checkboxes + an explicit
//    Save), not an inline checkbox grid mutating on contact, and not an
//    overflow menu — ui/dropdown-menu/* still has zero real consumers
//    anywhere in this codebase, and FE-3a already reasoned through (and
//    disclosed) why hand-rolling that primitive's first adoption via h()
//    render functions, unverifiable without a browser, is disproportionate
//    risk for a presentational requirement. Same call here.
//  - Self-lockout guard: the signed-in admin's own row can't Suspend itself,
//    and its own PLATFORM_ADMIN checkbox is disabled in the roles dialog —
//    mirrored independently by the backend (PlatformAdminService.suspend/
//    revokeRole now take the acting admin's id and refuse self-targeting
//    regardless of how many other admins remain).
//  - The create dialog's copy is intentionally NOT spec's literal "defaults
//    to read-only access" — there is no read-only platform tier; a zero-role
//    admin is refused at the BFF boundary before any session exists at all.
//    The existing, accurate copy ("cannot sign in... until PLATFORM_ADMIN is
//    assigned") stays.
import { computed, defineComponent, h, reactive, ref, watch, type PropType, type VNode } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import StateBadge, { platformAdminStatusTone } from '@/components/common/StateBadge.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import { describeAdminError, type AdminResult } from '@/api/problem'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'
import {
  activateAdmin,
  assignRole,
  createAdmin,
  getAdminRoles,
  listAdmins,
  revokeRole,
  suspendAdmin,
  PLATFORM_ROLES,
  type PlatformAdminView,
  type PlatformRoleName,
} from '@/api/platformAdmins'
import type { PageView } from '@/api/platformAdminTenants'

const sessionStore = usePlatformAdminSessionStore()
const ownAdminId = computed(() => (sessionStore.state.kind === 'active' ? sessionStore.state.adminId : null))

const page = ref(0)
const pageData = ref<PageView<PlatformAdminView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

// Roles are fetched per-admin, on demand — the list endpoint doesn't carry
// them (PlatformAdminView has no roles field). Keyed by admin id.
const rolesByAdmin = ref<Record<string, string[]>>({})
const rolesLoading = ref<Record<string, boolean>>({})

async function loadRolesFor(adminId: string): Promise<void> {
  rolesLoading.value = { ...rolesLoading.value, [adminId]: true }
  const result = await getAdminRoles(adminId)
  if (result.kind === 'ok') {
    rolesByAdmin.value = { ...rolesByAdmin.value, [adminId]: result.value }
  }
  rolesLoading.value = { ...rolesLoading.value, [adminId]: false }
}

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listAdmins(page.value)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      await Promise.all(result.value.items.map((admin) => loadRolesFor(admin.id)))
      break
    case 'reauth':
      // Never actually produced on this surface — parsePlatformResult maps a
      // session-expired outcome to the 'error' arm below instead.
      break
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

void load()
watch(page, load)

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

function fullName(admin: PlatformAdminView): string {
  return [admin.firstName, admin.lastName].filter(Boolean).join(' ') || '—'
}

// ---- create dialog ------------------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const createForm = reactive({ email: '', password: '', firstName: '', lastName: '' })
const createErrors = reactive<Record<string, string>>({})
const createBanner = ref('')
const createSuccessMessage = ref('')

function resetCreateForm(): void {
  createForm.email = ''
  createForm.password = ''
  createForm.firstName = ''
  createForm.lastName = ''
  createBanner.value = ''
  for (const key of Object.keys(createErrors)) delete createErrors[key]
}

function handleCreateOpenChange(open: boolean): void {
  isCreateOpen.value = open
  if (!open) resetCreateForm()
}

async function handleCreate(): Promise<void> {
  if (isCreating.value) return
  createBanner.value = ''
  for (const key of Object.keys(createErrors)) delete createErrors[key]

  isCreating.value = true
  try {
    const result = await createAdmin({
      email: createForm.email,
      password: createForm.password,
      firstName: createForm.firstName || undefined,
      lastName: createForm.lastName || undefined,
    })
    switch (result.kind) {
      case 'ok':
        isCreateOpen.value = false
        createSuccessMessage.value =
          `Created ${result.value.email}. This admin holds no platform roles yet and cannot sign in to ` +
          `this console until PLATFORM_ADMIN is assigned below.`
        resetCreateForm()
        page.value = 0
        await load()
        break
      case 'validationErrors':
        Object.assign(createErrors, result.errors)
        break
      case 'conflict':
        createBanner.value = result.code === 'platform_admin.email_exists' ? 'A platform admin with this email already exists.' : result.message
        break
      case 'reauth':
        break
      default:
        createBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isCreating.value = false
  }
}

// ---- lifecycle actions (suspend/activate, both confirmed) ----------------

const actionPending = ref(false)
const actionError = ref('')
const confirmState = ref<{ adminId: string; email: string; action: 'suspend' | 'activate' } | null>(null)

function startAction(admin: PlatformAdminView, action: 'suspend' | 'activate'): void {
  if (actionPending.value) return
  actionError.value = ''
  confirmState.value = { adminId: admin.id, email: admin.email, action }
}

function cancelAction(): void {
  confirmState.value = null
}

async function confirmAction(): Promise<void> {
  if (!confirmState.value || actionPending.value) return
  const { adminId, action } = confirmState.value
  actionError.value = ''
  actionPending.value = true
  try {
    const result = action === 'suspend' ? await suspendAdmin(adminId) : await activateAdmin(adminId)
    switch (result.kind) {
      case 'ok':
        confirmState.value = null
        await load()
        break
      case 'conflict':
        actionError.value =
          result.code === 'platform_admin.last_admin'
            ? 'This is the last active PLATFORM_ADMIN. Assign PLATFORM_ADMIN to another active admin first.'
            : result.message
        break
      case 'reauth':
        break
      default:
        // Rare race: the frontend guard already disables self-targeting
        // Suspend, but the backend's own independent check is what actually
        // guarantees this can never succeed.
        actionError.value =
          result.code === 'platform_admin.self_action_refused'
            ? "You can't do that to your own account."
            : describeAdminError(result)
        break
    }
  } finally {
    actionPending.value = false
  }
}

const confirmTitle = computed(() =>
  confirmState.value?.action === 'suspend' ? `Suspend ${confirmState.value.email}?` : `Activate ${confirmState.value?.email}?`,
)
const confirmDescription = computed(() =>
  confirmState.value?.action === 'suspend'
    ? 'Takes effect immediately: their live token is revoked within seconds, not at its 5-minute expiry.'
    : 'This admin will be able to sign in again (if they hold at least one platform role).',
)

// ---- role assignment: a Save-gated "Manage roles" dialog ------------------

interface RolesDialogState {
  adminId: string
  email: string
  initialRoles: string[]
  selected: Set<string>
}

const rolesDialogState = ref<RolesDialogState | null>(null)
const rolesDialogPending = ref(false)
const rolesDialogError = ref('')

function openRolesDialog(admin: PlatformAdminView): void {
  const current = rolesByAdmin.value[admin.id] ?? []
  rolesDialogState.value = { adminId: admin.id, email: admin.email, initialRoles: current, selected: new Set(current) }
  rolesDialogError.value = ''
}

function closeRolesDialog(): void {
  rolesDialogState.value = null
}

function toggleRoleCheckbox(role: PlatformRoleName, checked: boolean): void {
  if (!rolesDialogState.value) return
  if (checked) rolesDialogState.value.selected.add(role)
  else rolesDialogState.value.selected.delete(role)
}

function describeRoleError(result: AdminResult<void>): string {
  switch (result.kind) {
    case 'conflict':
      return result.code === 'platform_admin.last_admin'
        ? 'This is the last active PLATFORM_ADMIN. Assign PLATFORM_ADMIN to another active admin before revoking it here.'
        : result.message
    case 'ok':
    case 'reauth':
      return ''
    default:
      return result.code === 'platform_admin.self_action_refused'
        ? "You can't remove your own PLATFORM_ADMIN role."
        : describeAdminError(result)
  }
}

async function saveRoles(): Promise<void> {
  if (!rolesDialogState.value || rolesDialogPending.value) return
  const { adminId, initialRoles, selected } = rolesDialogState.value
  rolesDialogError.value = ''
  rolesDialogPending.value = true
  try {
    const toAssign = PLATFORM_ROLES.filter((r) => selected.has(r) && !initialRoles.includes(r))
    const toRevoke = PLATFORM_ROLES.filter((r) => !selected.has(r) && initialRoles.includes(r))
    for (const role of toAssign) {
      const result = await assignRole(adminId, role)
      if (result.kind !== 'ok') {
        rolesDialogError.value = describeRoleError(result)
        return
      }
    }
    for (const role of toRevoke) {
      const result = await revokeRole(adminId, role)
      if (result.kind !== 'ok') {
        rolesDialogError.value = describeRoleError(result)
        return
      }
    }
    await loadRolesFor(adminId)
    rolesDialogState.value = null
  } finally {
    rolesDialogPending.value = false
  }
}

// ---- DataTable columns ----------------------------------------------------

const AdminActionsCell = defineComponent({
  props: { admin: { type: Object as PropType<PlatformAdminView>, required: true } },
  setup(props) {
    return (): VNode => {
      const admin = props.admin
      const isSelf = admin.id === ownAdminId.value
      const children: VNode[] = []

      if (admin.status === 'SUSPENDED') {
        children.push(
          h(
            Button,
            {
              id: `admin-action-activate-${admin.id}`,
              size: 'sm',
              variant: 'outline',
              disabled: actionPending.value,
              onClick: () => startAction(admin, 'activate'),
            },
            { default: () => 'Activate' },
          ),
        )
      } else if (admin.status === 'ACTIVE') {
        children.push(
          h(
            Button,
            {
              id: `admin-action-suspend-${admin.id}`,
              size: 'sm',
              variant: 'destructive',
              disabled: actionPending.value || isSelf,
              title: isSelf ? "You can't suspend your own account." : undefined,
              onClick: () => startAction(admin, 'suspend'),
            },
            { default: () => 'Suspend' },
          ),
        )
      }

      if (admin.status !== 'DELETED') {
        children.push(
          h(
            Button,
            {
              id: `admin-manage-roles-${admin.id}`,
              size: 'sm',
              variant: 'outline',
              disabled: rolesLoading.value[admin.id],
              onClick: () => openRolesDialog(admin),
            },
            { default: () => 'Manage roles' },
          ),
        )
      }

      if (children.length === 0) {
        return h('span', { class: 'text-xs text-muted-foreground' }, 'No actions (terminal)')
      }
      return h('div', { class: 'flex flex-wrap items-center gap-2' }, children)
    }
  },
})

const columns = computed<ColumnDef<PlatformAdminView, unknown>[]>(() => [
  { id: 'email', header: 'Email', cell: ({ row }) => row.original.email },
  { id: 'name', header: 'Name', cell: ({ row }) => fullName(row.original) },
  {
    id: 'status',
    header: 'Status',
    cell: ({ row }) => h(StateBadge, { tone: platformAdminStatusTone(row.original.status), label: row.original.status }),
  },
  {
    id: 'roles',
    header: 'Roles',
    cell: ({ row }) => {
      const admin = row.original
      if (rolesLoading.value[admin.id]) return h('span', { class: 'text-xs text-muted-foreground' }, 'Loading…')
      const roles = rolesByAdmin.value[admin.id] ?? []
      if (roles.length === 0) return h('span', { class: 'text-xs text-muted-foreground' }, 'No roles — cannot sign in yet')
      return h('span', { class: 'text-xs font-mono' }, roles.join(', '))
    },
  },
  {
    id: 'lastLogin',
    header: 'Last login',
    cell: ({ row }) => (row.original.lastLoginAt ? h(RelativeTime, { value: row.original.lastLoginAt }) : h('span', { class: 'text-sm' }, 'Never')),
  },
  {
    id: 'actions',
    header: 'Actions',
    cell: ({ row }) => h(AdminActionsCell, { admin: row.original }),
  },
])
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Platform admins</h1>
        <p class="text-sm text-muted-foreground">Create CloseAuth staff admins and manage who holds PLATFORM_ADMIN.</p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleCreateOpenChange">
        <DialogTrigger as-child>
          <Button id="new-platform-admin-button">New platform admin</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a platform admin</DialogTitle>
            <DialogDescription>
              Creates the account only. It holds no platform roles by default and cannot sign in to this
              console until PLATFORM_ADMIN is assigned.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <FormField id="new-platform-admin-email" label="Email" :error="createErrors.email">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-platform-admin-email"
                  v-model="createForm.email"
                  type="email"
                  autocomplete="email"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <FormField id="new-platform-admin-password" label="Password" :error="createErrors.password">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-platform-admin-password"
                  v-model="createForm.password"
                  type="password"
                  autocomplete="new-password"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <div class="grid grid-cols-2 gap-3">
              <FormField id="new-platform-admin-first-name" label="First name" :error="createErrors.firstName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="new-platform-admin-first-name"
                    v-model="createForm.firstName"
                    type="text"
                    autocomplete="given-name"
                    :disabled="isCreating"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
              <FormField id="new-platform-admin-last-name" label="Last name" :error="createErrors.lastName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="new-platform-admin-last-name"
                    v-model="createForm.lastName"
                    type="text"
                    autocomplete="family-name"
                    :disabled="isCreating"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
            </div>

            <p v-if="createBanner" role="alert" class="text-sm text-destructive">{{ createBanner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">
                {{ isCreating ? 'Creating…' : 'Create' }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <p v-if="createSuccessMessage" role="status" class="text-sm rounded-md border border-border bg-muted p-3">
      {{ createSuccessMessage }}
    </p>
    <p v-if="actionError" role="alert" class="text-sm text-destructive">{{ actionError }}</p>

    <DataTable
      :columns="columns"
      :data="pageData?.items ?? []"
      :row-key="(a: PlatformAdminView) => a.id"
      :state="dataTableState"
      :row-attrs="(a: PlatformAdminView) => ({ 'data-admin-id': a.id })"
      :page="pageData?.page ?? 0"
      :size="pageData?.size ?? 20"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      empty-title="No platform admins yet."
      empty-description="Create one to get started."
      search-placeholder="Search admins…"
      @update:page="(p: number) => (page = p)"
      @retry="load"
    >
      <template #action>
        <Button type="button" @click="isCreateOpen = true">New platform admin</Button>
      </template>
    </DataTable>

    <ConfirmDialog
      :open="confirmState !== null"
      :title="confirmTitle"
      :description="confirmDescription"
      :confirm-label="confirmState?.action === 'suspend' ? 'Suspend' : 'Activate'"
      :destructive="confirmState?.action === 'suspend'"
      :pending="actionPending"
      @update:open="(open: boolean) => { if (!open) cancelAction() }"
      @confirm="confirmAction"
    />

    <!-- FE-3c: role assign/revoke as a deliberate, Save-gated second step —
         never a checkbox that mutates on contact. -->
    <Dialog :open="rolesDialogState !== null" @update:open="(open: boolean) => { if (!open) closeRolesDialog() }">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Manage roles — {{ rolesDialogState?.email }}</DialogTitle>
          <DialogDescription>
            Changes take effect only when you click Save. Assigning PLATFORM_ADMIN lets this admin sign in;
            revoking it (if it's their only role) locks them out.
          </DialogDescription>
        </DialogHeader>

        <div v-if="rolesDialogState" class="flex flex-col gap-2">
          <div v-for="role in PLATFORM_ROLES" :key="role" class="flex items-center gap-2">
            <Checkbox
              :id="`roles-dialog-${role}`"
              :model-value="rolesDialogState.selected.has(role)"
              :disabled="rolesDialogPending || (role === 'PLATFORM_ADMIN' && rolesDialogState.adminId === ownAdminId)"
              @update:model-value="(v) => toggleRoleCheckbox(role, Boolean(v))"
            />
            <Label :for="`roles-dialog-${role}`" class="font-mono text-sm">{{ role }}</Label>
            <span v-if="role === 'PLATFORM_ADMIN' && rolesDialogState.adminId === ownAdminId" class="text-xs text-muted-foreground">
              You can't remove your own PLATFORM_ADMIN role.
            </span>
          </div>
        </div>

        <p v-if="rolesDialogError" role="alert" class="text-sm text-destructive">{{ rolesDialogError }}</p>

        <DialogFooter>
          <Button id="roles-dialog-cancel" type="button" variant="outline" :disabled="rolesDialogPending" @click="closeRolesDialog">
            Cancel
          </Button>
          <Button id="roles-dialog-save" type="button" :disabled="rolesDialogPending" @click="saveRoles">
            {{ rolesDialogPending ? 'Saving…' : 'Save' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
