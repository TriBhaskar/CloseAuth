<script setup lang="ts">
// Stage UI-4: the platform console's platform-admin management surface —
// list, a create dialog, suspend/activate, and role assign/revoke. Roles are
// readable via GET /admins/{id}/roles (the UI-4 backend addition,
// PlatformAdminManagementController.roles) — without it this view could not
// show who actually holds PLATFORM_ADMIN, the single fact that determines
// console access.
//
// No detail route (same "deliberately small" discipline as
// PlatformTenantsView.vue): role management happens inline, per row, via an
// expand-in-place panel rather than a separate page.
import { onMounted, reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  activateAdmin,
  assignRole,
  createAdmin,
  getAdminRoles,
  listAdmins,
  revokeRole,
  suspendAdmin,
  PLATFORM_ROLES,
  type PlatformAdminStatus,
  type PlatformAdminView,
  type PlatformRoleName,
} from '@/api/platformAdmins'
import type { PageView } from '@/api/platformAdminTenants'

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
      // session-expired outcome to the 'error' arm below instead (there is
      // no silent-navigation path here; see platformAdminClient.ts's header
      // comment). Kept only so the switch is exhaustive over AdminResult<T>.
      break
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)
watch(page, load)

function statusVariant(status: PlatformAdminStatus): 'default' | 'destructive' | 'outline' {
  switch (status) {
    case 'ACTIVE':
      return 'default'
    case 'SUSPENDED':
      return 'destructive'
    case 'DELETED':
      return 'outline'
  }
}

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
        // Never actually produced on this surface — see load()'s identical comment.
        break
      default:
        createBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isCreating.value = false
  }
}

// ---- lifecycle actions ----------------------------------------------------

const actionPending = ref(false)
const actionError = ref('')
const confirmAdminId = ref<string | null>(null)

function startSuspend(adminId: string): void {
  if (actionPending.value) return
  actionError.value = ''
  confirmAdminId.value = adminId
}

async function runSuspend(adminId: string): Promise<void> {
  if (actionPending.value) return
  actionError.value = ''
  actionPending.value = true
  try {
    const result = await suspendAdmin(adminId)
    switch (result.kind) {
      case 'ok':
        confirmAdminId.value = null
        await load()
        break
      case 'conflict':
        actionError.value =
          result.code === 'platform_admin.last_admin'
            ? 'This is the last active PLATFORM_ADMIN. Assign PLATFORM_ADMIN to another active admin first.'
            : result.message
        break
      case 'reauth':
        // Never actually produced on this surface — see load()'s identical comment.
        break
      default:
        actionError.value = describeAdminError(result)
        break
    }
  } finally {
    actionPending.value = false
  }
}

async function runActivate(adminId: string): Promise<void> {
  if (actionPending.value) return
  actionError.value = ''
  actionPending.value = true
  try {
    const result = await activateAdmin(adminId)
    // 'reauth' is excluded explicitly (never actually produced on this
    // surface — see load()'s comment) so describeAdminError's narrower
    // parameter type accepts the rest.
    if (result.kind !== 'ok' && result.kind !== 'reauth') actionError.value = describeAdminError(result)
    else if (result.kind === 'ok') await load()
  } finally {
    actionPending.value = false
  }
}

// ---- role assignment -----------------------------------------------------

const roleActionPending = ref(false)
const roleActionError = ref('')

async function toggleRole(adminId: string, role: PlatformRoleName, currentlyHeld: boolean): Promise<void> {
  if (roleActionPending.value) return
  roleActionError.value = ''
  roleActionPending.value = true
  try {
    const result = currentlyHeld ? await revokeRole(adminId, role) : await assignRole(adminId, role)
    switch (result.kind) {
      case 'ok':
        await loadRolesFor(adminId)
        break
      case 'conflict':
        roleActionError.value =
          result.code === 'platform_admin.last_admin'
            ? 'This is the last active PLATFORM_ADMIN. Assign PLATFORM_ADMIN to another active admin before revoking it here.'
            : result.message
        break
      case 'reauth':
        // Never actually produced on this surface — see load()'s identical comment.
        break
      default:
        roleActionError.value = describeAdminError(result)
        break
    }
  } finally {
    roleActionPending.value = false
  }
}
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
    <p v-if="roleActionError" role="alert" class="text-sm text-destructive">{{ roleActionError }}</p>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead>Last login</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="6">
                No platform admins yet.
              </TableEmpty>
              <TableRow v-for="admin in pageData?.items ?? []" :key="admin.id" :data-admin-id="admin.id">
                <TableCell>{{ admin.email }}</TableCell>
                <TableCell>{{ fullName(admin) }}</TableCell>
                <TableCell><Badge :variant="statusVariant(admin.status)">{{ admin.status }}</Badge></TableCell>
                <TableCell>
                  <div v-if="rolesLoading[admin.id]" class="text-xs text-muted-foreground">Loading…</div>
                  <div v-else class="flex flex-col gap-1">
                    <p v-if="(rolesByAdmin[admin.id] ?? []).length === 0" class="text-xs text-muted-foreground">
                      No roles — cannot sign in yet
                    </p>
                    <div v-for="role in PLATFORM_ROLES" :key="role" class="flex items-center gap-1.5">
                      <Checkbox
                        :id="`role-${admin.id}-${role}`"
                        :model-value="(rolesByAdmin[admin.id] ?? []).includes(role)"
                        :disabled="roleActionPending"
                        @update:model-value="() => toggleRole(admin.id, role, (rolesByAdmin[admin.id] ?? []).includes(role))"
                      />
                      <Label :for="`role-${admin.id}-${role}`" class="text-xs font-mono">{{ role }}</Label>
                    </div>
                  </div>
                </TableCell>
                <TableCell>{{ admin.lastLoginAt ? new Date(admin.lastLoginAt).toLocaleString() : 'Never' }}</TableCell>
                <TableCell>
                  <div class="flex items-center gap-2">
                    <Button
                      v-if="admin.status === 'SUSPENDED'"
                      :id="`admin-action-activate-${admin.id}`"
                      size="sm"
                      variant="outline"
                      :disabled="actionPending"
                      @click="runActivate(admin.id)"
                    >
                      Activate
                    </Button>
                    <Button
                      v-else-if="admin.status === 'ACTIVE'"
                      :id="`admin-action-suspend-${admin.id}`"
                      size="sm"
                      variant="destructive"
                      :disabled="actionPending"
                      @click="startSuspend(admin.id)"
                    >
                      Suspend
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>

        <AdminPagination
          v-if="pageData"
          :page="pageData.page"
          :size="pageData.size"
          :total-elements="pageData.totalElements"
          :total-pages="pageData.totalPages"
          @update:page="(p) => (page = p)"
        />
      </div>
    </QueryState>

    <ConfirmDialog
      :open="confirmAdminId !== null"
      title="Suspend this platform admin?"
      description="Takes effect immediately: their live token is revoked within seconds, not at its 5-minute expiry."
      confirm-label="Suspend"
      :pending="actionPending"
      @update:open="(open: boolean) => { if (!open) confirmAdminId = null }"
      @confirm="() => confirmAdminId && runSuspend(confirmAdminId)"
    />
  </div>
</template>
