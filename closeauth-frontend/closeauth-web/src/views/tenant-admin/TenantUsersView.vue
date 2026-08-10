<script setup lang="ts">
// Stage UI-3b: the console's first real CRUD surface. List + paging + create
// — the reusable pattern (QueryState -> table -> AdminPagination, a Dialog
// form using FormField for per-field validation display) every later
// surface (clients, resource servers, roles, branding, audit) copies.
//
// No search/filter/sort: TenantUserController's list endpoint takes only
// page/size (identity/dto — verified against the controller source, not
// just the docs). A client-side filter over one fetched page would
// misrepresent what it's filtering, which is the same class of dishonesty
// as the no-fake-data rule this stage otherwise enforces — so there isn't one.
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import UserStatusBadge from '@/components/admin/UserStatusBadge.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import { createUser, listUsers, DEFAULT_PAGE_SIZE, type PageView, type UserView } from '@/api/tenantAdminUsers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

const page = ref(0)
const pageData = ref<PageView<UserView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listUsers(slug, page.value, DEFAULT_PAGE_SIZE)
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
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)
watch(page, load)

function openDetail(userId: string): void {
  void router.push({ name: 'tenant-admin-user-detail', params: { slug, userId } })
}

function fullName(user: UserView): string {
  return [user.firstName, user.lastName].filter(Boolean).join(' ') || '—'
}

function formatDate(value: string | null): string {
  if (!value) return 'Never'
  return new Date(value).toLocaleString()
}

// ---- create user dialog ----------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const createForm = reactive({
  email: '',
  password: '',
  firstName: '',
  lastName: '',
  phone: '',
  initialStatus: 'PENDING' as 'PENDING' | 'ACTIVE',
})
const createErrors = reactive<Record<string, string>>({})
const createBanner = ref('')

function resetCreateForm(): void {
  createForm.email = ''
  createForm.password = ''
  createForm.firstName = ''
  createForm.lastName = ''
  createForm.phone = ''
  createForm.initialStatus = 'PENDING'
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
    const result = await createUser(slug, {
      email: createForm.email,
      password: createForm.password,
      firstName: createForm.firstName || undefined,
      lastName: createForm.lastName || undefined,
      phone: createForm.phone || undefined,
      initialStatus: createForm.initialStatus,
    })
    switch (result.kind) {
      case 'ok':
        isCreateOpen.value = false
        resetCreateForm()
        page.value = 0
        await load()
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
  } finally {
    isCreating.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Users</h1>
        <p class="text-sm text-muted-foreground">Manage this tenant's users, lifecycle, and role assignments.</p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleCreateOpenChange">
        <DialogTrigger as-child>
          <Button id="new-user-button">New user</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a user</DialogTitle>
            <DialogDescription>Adds a password-based user directly to this tenant.</DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
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

            <FormField id="new-user-password" label="Password" :error="createErrors.password">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-user-password"
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
              <FormField id="new-user-first-name" label="First name" :error="createErrors.firstName">
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
              <Label for="new-user-initial-status">Initial status</Label>
              <select
                id="new-user-initial-status"
                v-model="createForm.initialStatus"
                :disabled="isCreating"
                class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
              >
                <option value="PENDING">Pending (requires approval before sign-in)</option>
                <option value="ACTIVE">Active (can sign in immediately)</option>
              </select>
            </div>

            <p v-if="createBanner" role="alert" class="text-sm text-destructive">{{ createBanner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">
                {{ isCreating ? 'Creating…' : 'Create user' }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Verified</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Last login</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="6">
                No users yet.
              </TableEmpty>
              <TableRow
                v-for="user in pageData?.items ?? []"
                :key="user.id"
                :data-user-id="user.id"
                class="cursor-pointer"
                @click="openDetail(user.id)"
              >
                <TableCell>{{ user.email }}</TableCell>
                <TableCell><UserStatusBadge :status="user.status" /></TableCell>
                <TableCell>{{ fullName(user) }}</TableCell>
                <TableCell>{{ user.emailVerified ? 'Yes' : 'No' }}</TableCell>
                <TableCell>{{ new Date(user.createdAt).toLocaleDateString() }}</TableCell>
                <TableCell>{{ formatDate(user.lastLoginAt) }}</TableCell>
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
  </div>
</template>
