<script setup lang="ts">
// Stage UI-3d: tenant-role CRUD — list + dialogs, not a detail route:
// description and isDefault are this tier's only mutable fields, so a
// dedicated detail page would hold nothing the row + a dialog doesn't
// already show (unlike application roles, which get their own detail
// route for scope bundling — see TenantApplicationRoleDetailView.vue).
//
// System roles (TENANT_ADMIN, TENANT_MEMBER, BILLING_ADMIN — the starter
// pack) render with NO edit/delete control at all, per tenantRoleActions —
// update/delete both 403 role.system_immutable on the backend, so offering
// them here would be an action the backend must refuse. Assignment/
// revocation for a system role stay available on the user-detail view.
//
// FE-4b: rebuilt onto DataTable. System rows gain a Lock icon (spec
// §6.4.5's literal ask) alongside the existing "System — cannot be
// changed" copy. A new "Assignees" action opens a read-only dialog listing
// everyone holding the role (TenantRoleController.assignees, new this
// session) — a dialog rather than a new detail route/expandable row, since
// this tier otherwise has no detail page to extend and DataTable has no
// built-in row-expansion wired up. No server-side role search exists (GET
// /roles still takes only page/size), so — same precedent as the other two
// FE-4b list rebuilds — a larger page is fetched once and filtered
// client-side.
import { computed, h, onMounted, reactive, ref, type VNode } from 'vue'
import { useRoute } from 'vue-router'
import { Lock } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { describeAdminError } from '@/api/problem'
import {
  createRole,
  deleteRole,
  getRoleAssignees,
  listRolesPaged,
  tenantRoleActions,
  updateRole,
  TENANT_ROLE_CONFLICT_FIELDS,
  type RoleAssigneeView,
  type TenantRoleView,
} from '@/api/tenantAdminRoles'

const route = useRoute()
const slug = String(route.params.slug ?? '')

// The whole catalog in one page — no server-side search exists, so
// DataTable filters this client-side (searchQuery below). Mirrors
// ROLE_CATALOG_PAGE_SIZE's "whole catalog" convention elsewhere in this
// codebase.
const ROLE_LIST_PAGE_SIZE = 100

const roles = ref<TenantRoleView[]>([])
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const searchQuery = ref('')

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listRolesPaged(slug, 0, ROLE_LIST_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      roles.value = result.value.items
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      roles.value = []
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

const hasActiveFilters = computed(() => searchQuery.value.trim().length > 0)

const filteredRoles = computed<TenantRoleView[]>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return roles.value
  return roles.value.filter(
    (r) => r.name.toLowerCase().includes(q) || (r.description ?? '').toLowerCase().includes(q),
  )
})

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

// ---- assignees dialog (new, FE-4b) --------------------------------------

const assigneesRole = ref<TenantRoleView | null>(null)
const assignees = ref<RoleAssigneeView[]>([])
const isAssigneesLoading = ref(false)
const assigneesError = ref<string | null>(null)

async function openAssignees(role: TenantRoleView): Promise<void> {
  assigneesRole.value = role
  assignees.value = []
  assigneesError.value = null
  isAssigneesLoading.value = true
  const result = await getRoleAssignees(slug, role.id)
  switch (result.kind) {
    case 'ok':
      assignees.value = result.value
      isAssigneesLoading.value = false
      break
    case 'reauth':
      break
    default:
      assigneesError.value = describeAdminError(result)
      isAssigneesLoading.value = false
      break
  }
}

function closeAssigneesDialog(open: boolean): void {
  if (!open) assigneesRole.value = null
}

function assigneeName(a: RoleAssigneeView): string {
  return [a.firstName, a.lastName].filter(Boolean).join(' ') || '—'
}

// ---- DataTable columns ----------------------------------------------------

const columns = computed<ColumnDef<TenantRoleView, unknown>[]>(() => [
  {
    id: 'name',
    header: 'Name',
    cell: ({ row }): VNode | string => {
      if (!row.original.isSystem) return row.original.name
      return h('div', { class: 'flex items-center gap-1.5' }, [
        h(Lock, { class: 'h-3.5 w-3.5 text-muted-foreground shrink-0' }),
        h('span', row.original.name),
      ])
    },
  },
  { id: 'description', header: 'Description', cell: ({ row }) => row.original.description || '—' },
  { id: 'default', header: 'Default', cell: ({ row }) => (row.original.isDefault ? 'Yes' : 'No') },
  {
    id: 'system',
    header: 'System',
    cell: ({ row }) => (row.original.isSystem ? h(Badge, { variant: 'secondary' }, { default: () => 'System' }) : '—'),
  },
  {
    id: 'actions',
    header: 'Actions',
    cell: ({ row }): VNode => {
      const role = row.original
      const actions = tenantRoleActions(role)
      const children: VNode[] = [
        h(Button, { id: `role-assignees-${role.id}`, variant: 'outline', size: 'sm', onClick: () => openAssignees(role) }, { default: () => 'Assignees' }),
      ]
      if (actions.includes('edit')) {
        children.push(h(Button, { id: `role-edit-${role.id}`, variant: 'outline', size: 'sm', onClick: () => openEdit(role) }, { default: () => 'Edit' }))
      }
      if (actions.includes('delete')) {
        children.push(h(Button, { id: `role-delete-${role.id}`, variant: 'destructive', size: 'sm', onClick: () => (pendingDelete.value = role) }, { default: () => 'Delete' }))
      }
      if (actions.length === 0) {
        children.push(h('span', { class: 'text-xs text-muted-foreground' }, 'System — cannot be changed'))
      }
      return h('div', { class: 'flex flex-wrap items-center gap-2' }, children)
    },
  },
])
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

    <DataTable
      :columns="columns"
      :data="filteredRoles"
      :row-key="(r: TenantRoleView) => r.id"
      :state="dataTableState"
      :row-attrs="(r: TenantRoleView) => ({ 'data-role-id': r.id })"
      :page="0"
      :size="ROLE_LIST_PAGE_SIZE"
      :total-elements="roles.length"
      :total-pages="1"
      :error-message="errorMessage ?? undefined"
      :has-active-filters="hasActiveFilters"
      empty-title="No tenant roles yet."
      empty-description="Create one to get started."
      filtered-empty-title="No roles match your search."
      filtered-empty-description="Try a different name or description."
      search-placeholder="Search roles…"
      @update:search="(q: string) => (searchQuery = q)"
      @retry="load"
    />
    <p v-if="deleteError" role="alert" class="text-sm text-destructive">{{ deleteError }}</p>

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

    <Dialog :open="assigneesRole !== null" @update:open="closeAssigneesDialog">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Assignees — {{ assigneesRole?.name }}</DialogTitle>
          <DialogDescription>Everyone currently holding this role.</DialogDescription>
        </DialogHeader>
        <div class="flex flex-col gap-2">
          <p v-if="isAssigneesLoading" class="text-sm text-muted-foreground">Loading…</p>
          <p v-else-if="assigneesError" role="alert" class="text-sm text-destructive">{{ assigneesError }}</p>
          <p v-else-if="assignees.length === 0" class="text-sm text-muted-foreground">No one holds this role yet.</p>
          <ul v-else class="flex flex-col gap-2">
            <li v-for="a in assignees" :key="a.userId" :data-assignee-id="a.userId" class="flex flex-col gap-0.5 rounded-md border border-line p-2 text-sm">
              <span>{{ a.email }}</span>
              <span class="text-xs text-muted-foreground">{{ assigneeName(a) }} · {{ a.status }}</span>
            </li>
          </ul>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="assigneesRole = null">Close</Button>
        </DialogFooter>
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
