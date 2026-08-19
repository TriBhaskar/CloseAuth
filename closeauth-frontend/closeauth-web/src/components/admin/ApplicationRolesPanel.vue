<script setup lang="ts">
// Stage UI-3d: the application-role list for one resource server — mounted
// into TenantResourceServerDetailView.vue below the existing scopes panel.
// Extracted as a child component (rather than inlined, the way scopes are)
// because that view is already 536 lines; it takes { slug, rsId } as props
// rather than reading route params itself, so it composes cleanly inside
// the RS detail page.
//
// List + create dialog + delete confirm, same list-then-dialogs shape as
// TenantRolesView.vue. Editing (description/isDefault) and scope bundling
// both live on the row's own detail route
// (TenantApplicationRoleDetailView.vue) rather than here — a role's scope
// bundle needs real screen space, so "click a row" beats "another dialog on
// top of this one."
//
// No isSystem gating in this tier's actions — see
// tenantAdminApplicationRoles.ts's file header for why (createApplicationRole
// hardcodes is_system=false).
//
// FE-4b: rebuilt onto DataTable. No server-side search exists (GET
// .../roles still takes only page/size), so — same FE-3a/FE-4b precedent as
// the resource-servers list and the scope catalog — a larger page is
// fetched once and filtered client-side.
import { computed, h, onMounted, reactive, ref, watch, type VNode } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import { describeAdminError } from '@/api/problem'
import {
  createApplicationRole,
  deleteApplicationRole,
  listApplicationRoles,
  APPLICATION_ROLE_CONFLICT_FIELDS,
  type ApplicationRoleView,
} from '@/api/tenantAdminApplicationRoles'

const props = defineProps<{ slug: string; rsId: string }>()
const router = useRouter()

// The whole catalog in one page — mirrors SCOPE_CATALOG_PAGE_SIZE in
// TenantResourceServerDetailView.vue, this panel's sibling on the same page.
const ROLE_LIST_PAGE_SIZE = 100

const roles = ref<ApplicationRoleView[]>([])
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const searchQuery = ref('')

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listApplicationRoles(props.slug, props.rsId, 0, ROLE_LIST_PAGE_SIZE)
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
watch(() => props.rsId, () => void load())

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

const hasActiveFilters = computed(() => searchQuery.value.trim().length > 0)

const filteredRoles = computed<ApplicationRoleView[]>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return roles.value
  return roles.value.filter(
    (r) => r.name.toLowerCase().includes(q) || (r.description ?? '').toLowerCase().includes(q),
  )
})

function openDetail(roleId: string): void {
  void router.push({ name: 'tenant-admin-application-role-detail', params: { slug: props.slug, rsId: props.rsId, roleId } })
}

// ---- create dialog ---------------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const form = reactive({ name: '', description: '', isDefault: false })
const errors = reactive<Record<string, string>>({})
const banner = ref('')

function resetForm(): void {
  form.name = ''
  form.description = ''
  form.isDefault = false
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]
}

function handleOpenChange(open: boolean): void {
  isCreateOpen.value = open
  if (!open) resetForm()
}

async function handleCreate(): Promise<void> {
  if (isCreating.value) return
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]

  isCreating.value = true
  try {
    const result = await createApplicationRole(props.slug, props.rsId, {
      name: form.name,
      description: form.description || undefined,
      isDefault: form.isDefault,
    })
    switch (result.kind) {
      case 'ok':
        isCreateOpen.value = false
        resetForm()
        await load()
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
        break
      case 'conflict': {
        const field = APPLICATION_ROLE_CONFLICT_FIELDS[result.code]
        if (field) errors[field] = result.message
        else banner.value = result.message
        break
      }
      case 'reauth':
        break
      default:
        banner.value = describeAdminError(result)
        break
    }
  } finally {
    isCreating.value = false
  }
}

// ---- delete --------------------------------------------------------------

const pendingDelete = ref<ApplicationRoleView | null>(null)
const isDeleting = ref(false)
const deleteError = ref('')

async function confirmDelete(): Promise<void> {
  if (isDeleting.value || !pendingDelete.value) return
  deleteError.value = ''
  isDeleting.value = true
  try {
    const result = await deleteApplicationRole(props.slug, props.rsId, pendingDelete.value.id)
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

const columns = computed<ColumnDef<ApplicationRoleView, unknown>[]>(() => [
  { id: 'name', header: 'Name', cell: ({ row }) => row.original.name },
  { id: 'description', header: 'Description', cell: ({ row }) => row.original.description || '—' },
  { id: 'default', header: 'Default', cell: ({ row }) => (row.original.isDefault ? 'Yes' : 'No') },
  {
    id: 'actions',
    header: 'Actions',
    cell: ({ row }): VNode => {
      const role = row.original
      return h('div', { class: 'flex items-center gap-2', onClick: (e: MouseEvent) => e.stopPropagation() }, [
        h(Button, { id: `app-role-open-${role.id}`, variant: 'outline', size: 'sm', onClick: () => openDetail(role.id) }, { default: () => 'Open' }),
        h(Button, { id: `app-role-delete-${role.id}`, variant: 'destructive', size: 'sm', onClick: () => (pendingDelete.value = role) }, { default: () => 'Delete' }),
      ])
    },
  },
])
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-semibold tracking-tight">Application roles</h2>
      <Dialog :open="isCreateOpen" @update:open="handleOpenChange">
        <DialogTrigger as-child>
          <Button id="new-app-role-button" size="sm">New application role</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create an application role</DialogTitle>
            <DialogDescription>
              The role name cannot be changed after creation. Scopes are bundled from the role's own detail page.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <FormField id="new-app-role-name" label="Name" :error="errors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-app-role-name"
                  v-model="form.name"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField id="new-app-role-description" label="Description" :error="errors.description">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-app-role-description"
                  v-model="form.description"
                  type="text"
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <div class="flex items-center gap-2">
              <Checkbox
                id="new-app-role-is-default"
                :model-value="form.isDefault"
                :disabled="isCreating"
                @update:model-value="(v) => (form.isDefault = Boolean(v))"
              />
              <Label for="new-app-role-is-default" class="font-normal">Default (auto-granted to new users)</Label>
            </div>

            <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

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
      :row-key="(r: ApplicationRoleView) => r.id"
      :state="dataTableState"
      :row-attrs="(r: ApplicationRoleView) => ({ 'data-application-role-id': r.id })"
      :on-row-click="(r: ApplicationRoleView) => openDetail(r.id)"
      :page="0"
      :size="ROLE_LIST_PAGE_SIZE"
      :total-elements="roles.length"
      :total-pages="1"
      :error-message="errorMessage ?? undefined"
      :has-active-filters="hasActiveFilters"
      empty-title="No application roles defined yet for this resource server."
      empty-description="Create one to get started."
      filtered-empty-title="No application roles match your search."
      filtered-empty-description="Try a different name or description."
      search-placeholder="Search application roles…"
      @update:search="(q: string) => (searchQuery = q)"
      @retry="load"
    />
    <p v-if="deleteError" role="alert" class="text-sm text-destructive">{{ deleteError }}</p>

    <ConfirmDialog
      :open="pendingDelete !== null"
      title="Delete this application role?"
      :description="`Every user holding '${pendingDelete?.name}' loses it immediately — CloseAuth cannot report how many users that affects.`"
      confirm-label="Delete"
      :pending="isDeleting"
      @update:open="(open) => { if (!open) pendingDelete = null }"
      @confirm="confirmDelete"
    />
  </div>
</template>
