<script setup lang="ts">
// Stage UI-3d: tenant-role CRUD — list + dialogs, the same
// list-then-dialogs pattern TenantResourceServersView.vue (UI-3c) uses, not
// a detail route: description and isDefault are this tier's only mutable
// fields, so a dedicated detail page would hold nothing the row + a dialog
// doesn't already show (unlike application roles, which get their own
// detail route for scope bundling — see TenantApplicationRoleDetailView.vue).
//
// System roles (TENANT_ADMIN, TENANT_MEMBER, BILLING_ADMIN — the starter
// pack) render with NO edit/delete control at all, per tenantRoleActions —
// update/delete both 403 role.system_immutable on the backend, so offering
// them here would be an action the backend must refuse. Assignment/
// revocation for a system role stay available on the user-detail view; this
// list deliberately does not offer revocation from the role's perspective —
// there is no "users holding this role" endpoint, so such a surface could
// only be blind.
//
// Delete copy names the real consequence: every user holding the role loses
// it immediately (ON DELETE CASCADE), and CloseAuth cannot report how many
// that is — deleteRole's doc comment in tenantAdminRoles.ts explains why.
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  createRole,
  deleteRole,
  listRolesPaged,
  tenantRoleActions,
  updateRole,
  DEFAULT_ROLE_PAGE_SIZE,
  TENANT_ROLE_CONFLICT_FIELDS,
  type TenantRoleView,
} from '@/api/tenantAdminRoles'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const slug = String(route.params.slug ?? '')

const page = ref(0)
const pageData = ref<PageView<TenantRoleView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listRolesPaged(slug, page.value, DEFAULT_ROLE_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
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

// ---- create dialog ---------------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const createForm = reactive({ name: '', description: '', isDefault: false })
const createErrors = reactive<Record<string, string>>({})
const createBanner = ref('')

function resetCreateForm(): void {
  createForm.name = ''
  createForm.description = ''
  createForm.isDefault = false
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
    const result = await createRole(slug, {
      name: createForm.name,
      description: createForm.description || undefined,
      isDefault: createForm.isDefault,
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
      case 'conflict': {
        const field = TENANT_ROLE_CONFLICT_FIELDS[result.code]
        if (field) createErrors[field] = result.message
        else createBanner.value = result.message
        break
      }
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

// ---- edit dialog -------------------------------------------------------

const editingRole = ref<TenantRoleView | null>(null)
const editForm = reactive({ description: '', isDefault: false })
const editErrors = reactive<Record<string, string>>({})
const editBanner = ref('')
const isSaving = ref(false)

function openEdit(role: TenantRoleView): void {
  editingRole.value = role
  editForm.description = role.description ?? ''
  editForm.isDefault = role.isDefault
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]
}

function closeEditDialog(open: boolean): void {
  if (!open) editingRole.value = null
}

async function handleEditSubmit(): Promise<void> {
  if (isSaving.value || !editingRole.value) return
  editBanner.value = ''
  for (const key of Object.keys(editErrors)) delete editErrors[key]

  isSaving.value = true
  try {
    // Full replacement — always both fields, pre-populated. See
    // UpdateTenantRolePayload's doc comment in tenantAdminRoles.ts.
    const result = await updateRole(slug, editingRole.value.id, {
      description: editForm.description || undefined,
      isDefault: editForm.isDefault,
    })
    switch (result.kind) {
      case 'ok':
        editingRole.value = null
        await load()
        break
      case 'validationErrors':
        Object.assign(editErrors, result.errors)
        break
      case 'conflict': {
        const field = TENANT_ROLE_CONFLICT_FIELDS[result.code]
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

// ---- delete --------------------------------------------------------------

const pendingDelete = ref<TenantRoleView | null>(null)
const isDeleting = ref(false)
const deleteError = ref('')

async function confirmDelete(): Promise<void> {
  if (isDeleting.value || !pendingDelete.value) return
  deleteError.value = ''
  isDeleting.value = true
  try {
    const result = await deleteRole(slug, pendingDelete.value.id)
    switch (result.kind) {
      case 'ok':
        pendingDelete.value = null
        await load()
        break
      case 'reauth':
        break
      default:
        deleteError.value = describeAdminError(result)
        break
    }
  } finally {
    isDeleting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Tenant roles</h1>
        <p class="text-sm text-muted-foreground">Manage this tenant's roles. System roles are immutable.</p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleCreateOpenChange">
        <DialogTrigger as-child>
          <Button id="new-role-button">New role</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a tenant role</DialogTitle>
            <DialogDescription>The role name cannot be changed after creation.</DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <FormField id="new-role-name" label="Name" :error="createErrors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-role-name"
                  v-model="createForm.name"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField id="new-role-description" label="Description" :error="createErrors.description">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-role-description"
                  v-model="createForm.description"
                  type="text"
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <div class="flex items-center gap-2">
              <Checkbox
                id="new-role-is-default"
                :model-value="createForm.isDefault"
                :disabled="isCreating"
                @update:model-value="(v) => (createForm.isDefault = Boolean(v))"
              />
              <Label for="new-role-is-default" class="font-normal">Default (auto-granted to new users)</Label>
            </div>

            <p v-if="createBanner" role="alert" class="text-sm text-destructive">{{ createBanner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">{{ isCreating ? 'Creating…' : 'Create role' }}</Button>
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
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Default</TableHead>
                <TableHead>System</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="5">No tenant roles yet.</TableEmpty>
              <TableRow v-for="role in pageData?.items ?? []" :key="role.id" :data-role-id="role.id">
                <TableCell class="font-medium">{{ role.name }}</TableCell>
                <TableCell>{{ role.description || '—' }}</TableCell>
                <TableCell>{{ role.isDefault ? 'Yes' : 'No' }}</TableCell>
                <TableCell>
                  <Badge v-if="role.isSystem" variant="secondary">System</Badge>
                  <span v-else>—</span>
                </TableCell>
                <TableCell>
                  <div v-if="tenantRoleActions(role).length > 0" class="flex items-center gap-2">
                    <Button
                      v-if="tenantRoleActions(role).includes('edit')"
                      :id="`role-edit-${role.id}`"
                      variant="outline"
                      size="sm"
                      @click="openEdit(role)"
                    >
                      Edit
                    </Button>
                    <Button
                      v-if="tenantRoleActions(role).includes('delete')"
                      :id="`role-delete-${role.id}`"
                      variant="destructive"
                      size="sm"
                      @click="pendingDelete = role"
                    >
                      Delete
                    </Button>
                  </div>
                  <span v-else class="text-xs text-muted-foreground">System — cannot be changed</span>
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
        <p v-if="deleteError" role="alert" class="text-sm text-destructive">{{ deleteError }}</p>
      </div>
    </QueryState>

    <Dialog :open="editingRole !== null" @update:open="closeEditDialog">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Edit role</DialogTitle>
          <DialogDescription>The role name is immutable and shown here read-only.</DialogDescription>
        </DialogHeader>
        <form id="role-edit-form" class="flex flex-col gap-4" novalidate @submit.prevent="handleEditSubmit">
          <div class="flex flex-col gap-1.5">
            <Label>Name</Label>
            <code id="role-edit-name-readonly" class="rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono">
              {{ editingRole?.name }}
            </code>
          </div>

          <FormField id="role-edit-description" label="Description" :error="editErrors.description">
            <template #default="{ hasError, describedBy }">
              <Input
                id="role-edit-description"
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
              id="role-edit-is-default"
              :model-value="editForm.isDefault"
              :disabled="isSaving"
              @update:model-value="(v) => (editForm.isDefault = Boolean(v))"
            />
            <Label for="role-edit-is-default" class="font-normal">Default (auto-granted to new users)</Label>
          </div>

          <p v-if="editBanner" role="alert" class="text-sm text-destructive">{{ editBanner }}</p>

          <DialogFooter>
            <Button id="role-edit-submit" type="submit" :disabled="isSaving">
              {{ isSaving ? 'Saving…' : 'Save changes' }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <ConfirmDialog
      :open="pendingDelete !== null"
      title="Delete this role?"
      :description="`Every user holding '${pendingDelete?.name}' loses it immediately — CloseAuth cannot report how many users that affects.`"
      confirm-label="Delete"
      :pending="isDeleting"
      @update:open="(open) => { if (!open) pendingDelete = null }"
      @confirm="confirmDelete"
    />
  </div>
</template>
