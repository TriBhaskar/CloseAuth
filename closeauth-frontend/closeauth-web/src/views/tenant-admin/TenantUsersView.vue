<script setup lang="ts">
// FE-4a (spec §6.4.2): the tenant console's Users list, rebuilt onto
// DataTable (its first tenant-console adoption — everywhere else in this
// console still uses raw ui/table + QueryState) with real, URL-synced
// server-side filtering. Stage UI-3b's own header comment on this file used
// to explain why filters didn't exist ("a client-side filter over one
// fetched page would misrepresent what it's filtering") — that's still true
// of a naive approach, but the actual fix (server-side status/role/q,
// TenantUserController.list + the BFF's userFilterQuery allow-list) landed
// this session, so the filters now exist for real rather than being worked
// around.
//
// Two create modes (spec §6.4.2), replacing the old single admin-typed-
// password form: "Send an invitation" (default — issueInvite, email only;
// the invitee sets their own name/password at registration and roles are
// assigned afterward as ordinary tenant-role assignment) and "Set a
// temporary password" (createUserWithTempCredential — server-generated,
// must_change_password=true, shown exactly once via SecretRevealPanel with
// the 7-day TTL disclosure, optional initial role assigned atomically).
import { computed, h, onMounted, reactive, ref, watch, type VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import StateBadge, { userStatusTone } from '@/components/common/StateBadge.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import FormField from '@/components/common/FormField.vue'
import SecretRevealPanel, { type SecretField } from '@/components/common/SecretRevealPanel.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import { useToast } from '@/composables/useToast'
import {
  listUsers,
  createUserWithTempCredential,
  DEFAULT_PAGE_SIZE,
  type PageView,
  type UserView,
  type UserStatus,
} from '@/api/tenantAdminUsers'
import { listRoles, type TenantRoleView } from '@/api/tenantAdminRoles'
import { issueInvite } from '@/api/tenantAdminInvites'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const { toast } = useToast()

// ---- list state, seeded from the URL so a reload restores the view -------

const page = ref(Number(route.query.page) || 0)
const statusFilter = ref<UserStatus | ''>(
  typeof route.query.status === 'string' ? (route.query.status as UserStatus) : '',
)
const roleFilter = ref<string>(typeof route.query.role === 'string' ? route.query.role : '')
const searchQuery = ref<string>(typeof route.query.q === 'string' ? route.query.q : '')

const pageData = ref<PageView<UserView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
// FE-6.1 (spec §7.3): true unless the failure was a 403/404/429 — retrying
// one of those would just fail identically, so DataTable's Retry action is
// suppressed for exactly those categories via errorStateProps().
const errorRetryable = ref(true)
const roleCatalog = ref<TenantRoleView[]>([])

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listUsers(slug, page.value, DEFAULT_PAGE_SIZE, {
    status: statusFilter.value || undefined,
    role: roleFilter.value || undefined,
    q: searchQuery.value || undefined,
  })
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      // tenantAdminFetch already performed the top-level navigation to
      // reauth — stay in the loading state rather than flashing an error
      // the user will never get to act on.
      break
    default: {
      pageData.value = null
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

async function loadRoleCatalog(): Promise<void> {
  const result = await listRoles(slug)
  if (result.kind === 'ok') roleCatalog.value = result.value.items
  // A catalog-fetch failure only degrades the role filter/initial-role
  // picker to "no options" — it must never block the users list itself.
}

onMounted(() => {
  void load()
  void loadRoleCatalog()
})
watch(page, load)

// Status/role filters are view-owned and URL-synced (DataTable owns only
// `q`/`page` itself — see its own header comment on why named filters are
// the caller's job). Both watchers reset to page 0 and merge into whatever
// query DataTable's own q/page sync already wrote, never clobbering it.
watch([statusFilter, roleFilter], () => {
  page.value = 0
  const query = {
    ...route.query,
    status: statusFilter.value || undefined,
    role: roleFilter.value || undefined,
    page: undefined,
  }
  void router.replace({ query })
  void load()
})

function handleSearchUpdate(q: string): void {
  searchQuery.value = q
  page.value = 0
  void load()
}

const hasActiveFilters = computed(
  () =>
    Boolean(statusFilter.value) || Boolean(roleFilter.value) || searchQuery.value.trim().length > 0,
)

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

function openDetail(user: UserView): void {
  void router.push({ name: 'tenant-admin-user-detail', params: { slug, userId: user.id } })
}

function fullName(user: UserView): string {
  return [user.firstName, user.lastName].filter(Boolean).join(' ') || '—'
}

// ---- create user dialog: mode choice (spec §6.4.2) ------------------------

type CreateMode = 'invite' | 'temp-password'

const isCreateOpen = ref(false)
const isCreating = ref(false)
const createMode = ref<CreateMode>('invite')
const createForm = reactive({
  email: '',
  firstName: '',
  lastName: '',
  phone: '',
  initialRoleId: '',
})
const createErrors = reactive<Record<string, string>>({})
const createBanner = ref('')

const successPanelOpen = ref(false)
const successPanelFields = ref<SecretField[]>([])

function resetCreateForm(): void {
  createMode.value = 'invite'
  createForm.email = ''
  createForm.firstName = ''
  createForm.lastName = ''
  createForm.phone = ''
  createForm.initialRoleId = ''
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
    if (createMode.value === 'invite') {
      const result = await issueInvite(slug, { email: createForm.email })
      switch (result.kind) {
        case 'ok':
          isCreateOpen.value = false
          resetCreateForm()
          toast({ title: `Invitation sent to ${result.value.email}`, type: 'success' })
          break
        case 'validationErrors':
          Object.assign(createErrors, result.errors)
          break
        case 'reauth':
          break
        default:
          createBanner.value = describeAdminError(result)
          break
      }
    } else {
      const result = await createUserWithTempCredential(slug, {
        email: createForm.email,
        firstName: createForm.firstName || undefined,
        lastName: createForm.lastName || undefined,
        phone: createForm.phone || undefined,
        initialRoleId: createForm.initialRoleId || undefined,
      })
      switch (result.kind) {
        case 'ok': {
          isCreateOpen.value = false
          resetCreateForm()
          page.value = 0
          await load()
          // §5/§7.4: "action produced a value the user must keep" -> Dialog
          // (SecretRevealPanel), never a toast — opened only after the
          // create dialog has fully closed, never nested inside it (the
          // same FE-3b precedent: SecretRevealPanel is itself a full Dialog).
          successPanelFields.value = [
            { id: 'email', label: 'Email', value: result.value.user.email, maskable: false },
            {
              id: 'temp-password',
              label: 'Temporary password',
              value: result.value.temporaryPassword,
              maskable: true,
              hint: `Expires ${new Date(result.value.temporaryPasswordExpiresAt).toLocaleString()} (7 days) — the account must change it at next sign-in.`,
            },
          ]
          successPanelOpen.value = true
          break
        }
        case 'validationErrors':
          Object.assign(createErrors, result.errors)
          break
        case 'reauth':
          break
        default:
          createBanner.value = describeAdminError(result)
          break
      }
    }
  } finally {
    isCreating.value = false
  }
}

function handleSuccessPanelContinue(): void {
  successPanelOpen.value = false
  successPanelFields.value = []
}

// ---- DataTable columns ----------------------------------------------------

const columns = computed<ColumnDef<UserView, unknown>[]>(() => [
  {
    id: 'email',
    header: 'Email',
    cell: ({ row }) =>
      h('div', { class: 'flex flex-col gap-1' }, [
        h(IdentifierChip, { kind: 'user', value: row.original.id }),
        h('span', { class: 'text-[0.75rem] text-muted-foreground' }, row.original.email),
      ]),
  },
  {
    id: 'name',
    header: 'Name',
    cell: ({ row }) => fullName(row.original),
  },
  {
    id: 'status',
    header: 'Status',
    cell: ({ row }) =>
      h(StateBadge, { tone: userStatusTone(row.original.status), label: row.original.status }),
  },
  {
    id: 'roles',
    header: 'Roles',
    cell: ({ row }) => {
      const roles = row.original.roles
      if (roles.length === 0)
        return h('span', { class: 'text-xs text-muted-foreground' }, 'No roles')
      const shown = roles.slice(0, 2)
      const rest = roles.length - shown.length
      const chips = shown.map((name) =>
        h(
          'span',
          {
            class:
              'inline-flex items-center rounded border border-line px-1.5 py-0.5 text-[0.6875rem]',
          },
          name,
        ),
      )
      if (rest > 0) chips.push(h('span', { class: 'text-xs text-muted-foreground' }, `+${rest}`))
      return h('div', { class: 'flex flex-wrap items-center gap-1' }, chips)
    },
  },
  {
    id: 'created',
    header: 'Created',
    cell: ({ row }) => h(RelativeTime, { value: row.original.createdAt }),
  },
  {
    id: 'actions',
    header: 'Actions',
    cell: (): VNode => h('span', { class: 'text-xs text-muted-foreground' }, 'View →'),
  },
])
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Users</h1>
        <p class="text-sm text-muted-foreground">
          Manage this tenant's users, lifecycle, and role assignments.
        </p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleCreateOpenChange">
        <DialogTrigger as-child>
          <Button id="new-user-button">New user</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a user</DialogTitle>
            <DialogDescription>Choose how they get access.</DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <fieldset class="flex flex-col gap-2">
              <legend class="sr-only">Create mode</legend>
              <label
                class="flex items-start gap-2 rounded-md border border-line p-3 cursor-pointer"
                :class="createMode === 'invite' ? 'border-primary' : ''"
              >
                <input
                  id="new-user-mode-invite"
                  v-model="createMode"
                  type="radio"
                  name="new-user-mode"
                  value="invite"
                  class="mt-1"
                  :disabled="isCreating"
                />
                <span class="flex flex-col gap-0.5">
                  <span class="text-sm font-medium">Send an invitation</span>
                  <span class="text-xs text-muted-foreground">
                    They receive an invite email and set their own password. Roles are assigned
                    afterward.
                  </span>
                </span>
              </label>
              <label
                class="flex items-start gap-2 rounded-md border border-line p-3 cursor-pointer"
                :class="createMode === 'temp-password' ? 'border-primary' : ''"
              >
                <input
                  id="new-user-mode-temp-password"
                  v-model="createMode"
                  type="radio"
                  name="new-user-mode"
                  value="temp-password"
                  class="mt-1"
                  :disabled="isCreating"
                />
                <span class="flex flex-col gap-0.5">
                  <span class="text-sm font-medium">Set a temporary password</span>
                  <span class="text-xs text-muted-foreground">
                    Generates a password shown once. The account must change it within 7 days at
                    next sign-in.
                  </span>
                </span>
              </label>
            </fieldset>

            <FormField id="new-user-email" label="Email" :error="createErrors.email">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-user-email"
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

            <template v-if="createMode === 'temp-password'">
              <div class="grid grid-cols-2 gap-3">
                <FormField
                  id="new-user-first-name"
                  label="First name"
                  :error="createErrors.firstName"
                >
                  <template #default="{ hasError, describedBy }">
                    <Input
                      id="new-user-first-name"
                      v-model="createForm.firstName"
                      type="text"
                      autocomplete="given-name"
                      :disabled="isCreating"
                      :aria-invalid="hasError"
                      :aria-describedby="describedBy"
                    />
                  </template>
                </FormField>
                <FormField id="new-user-last-name" label="Last name" :error="createErrors.lastName">
                  <template #default="{ hasError, describedBy }">
                    <Input
                      id="new-user-last-name"
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

              <FormField id="new-user-phone" label="Phone (optional)" :error="createErrors.phone">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="new-user-phone"
                    v-model="createForm.phone"
                    type="tel"
                    autocomplete="tel"
                    :disabled="isCreating"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>

              <div class="flex flex-col gap-1.5">
                <Label for="new-user-initial-role">Initial tenant role (optional)</Label>
                <select
                  id="new-user-initial-role"
                  v-model="createForm.initialRoleId"
                  :disabled="isCreating"
                  class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                >
                  <option value="">None</option>
                  <option v-for="role in roleCatalog" :key="role.id" :value="role.id">
                    {{ role.name }}
                  </option>
                </select>
              </div>
            </template>

            <p v-if="createBanner" role="alert" class="text-sm text-destructive">
              {{ createBanner }}
            </p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">
                {{
                  isCreating
                    ? createMode === 'invite'
                      ? 'Sending…'
                      : 'Creating…'
                    : createMode === 'invite'
                      ? 'Send invitation'
                      : 'Create user'
                }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <div class="flex flex-wrap items-center gap-3">
      <div class="flex flex-col gap-1.5">
        <Label for="users-filter-status" class="text-xs">Status</Label>
        <select
          id="users-filter-status"
          v-model="statusFilter"
          class="border-input h-9 rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
        >
          <option value="">Any</option>
          <option value="PENDING">Pending</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
          <option value="DELETED">Deleted</option>
        </select>
      </div>
      <div class="flex flex-col gap-1.5">
        <Label for="users-filter-role" class="text-xs">Role</Label>
        <select
          id="users-filter-role"
          v-model="roleFilter"
          class="border-input h-9 rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
        >
          <option value="">Any</option>
          <option v-for="role in roleCatalog" :key="role.id" :value="role.name">
            {{ role.name }}
          </option>
        </select>
      </div>
    </div>

    <DataTable
      :columns="columns"
      :data="pageData?.items ?? []"
      :row-key="(u: UserView) => u.id"
      :state="dataTableState"
      :row-attrs="(u: UserView) => ({ 'data-user-id': u.id })"
      :on-row-click="openDetail"
      :page="pageData?.page ?? page"
      :size="pageData?.size ?? DEFAULT_PAGE_SIZE"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      :error-retryable="errorRetryable"
      :has-active-filters="hasActiveFilters"
      empty-title="No users yet."
      empty-description="Create one to get started."
      filtered-empty-title="No users match these filters."
      filtered-empty-description="Try widening or clearing your search."
      search-placeholder="Search by email or name…"
      @update:page="(p: number) => (page = p)"
      @update:search="handleSearchUpdate"
      @retry="load"
    >
      <template #action>
        <Button size="sm" @click="isCreateOpen = true">New user</Button>
      </template>
    </DataTable>

    <SecretRevealPanel
      :open="successPanelOpen"
      title="User created"
      warning-message="This temporary password is shown once. Copy it now or share it securely — it cannot be retrieved again after you close this."
      :fields="successPanelFields"
      @continue="handleSuccessPanelContinue"
    />
  </div>
</template>
