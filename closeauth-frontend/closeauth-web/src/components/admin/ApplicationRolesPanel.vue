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
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  createApplicationRole,
  deleteApplicationRole,
  listApplicationRoles,
  APPLICATION_ROLE_CONFLICT_FIELDS,
  DEFAULT_APPLICATION_ROLE_PAGE_SIZE,
  type ApplicationRoleView,
} from '@/api/tenantAdminApplicationRoles'
import type { PageView } from '@/api/tenantAdminUsers'

const props = defineProps<{ slug: string; rsId: string }>()
const router = useRouter()

const page = ref(0)
const pageData = ref<PageView<ApplicationRoleView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listApplicationRoles(props.slug, props.rsId, page.value, DEFAULT_APPLICATION_ROLE_PAGE_SIZE)
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
watch(() => props.rsId, () => { page.value = 0; void load() })

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
        page.value = 0
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

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Default</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="4">
                No application roles defined yet for this resource server.
              </TableEmpty>
              <TableRow v-for="role in pageData?.items ?? []" :key="role.id" :data-application-role-id="role.id">
                <TableCell class="font-medium cursor-pointer" @click="openDetail(role.id)">{{ role.name }}</TableCell>
                <TableCell>{{ role.description || '—' }}</TableCell>
                <TableCell>{{ role.isDefault ? 'Yes' : 'No' }}</TableCell>
                <TableCell>
                  <div class="flex items-center gap-2">
                    <Button :id="`app-role-open-${role.id}`" variant="outline" size="sm" @click="openDetail(role.id)">
                      Open
                    </Button>
                    <Button
                      :id="`app-role-delete-${role.id}`"
                      variant="destructive"
                      size="sm"
                      @click="pendingDelete = role"
                    >
                      Delete
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
        <p v-if="deleteError" role="alert" class="text-sm text-destructive">{{ deleteError }}</p>
      </div>
    </QueryState>

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
