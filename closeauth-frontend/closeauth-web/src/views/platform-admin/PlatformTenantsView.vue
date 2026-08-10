<script setup lang="ts">
// Stage UI-4: the platform console's tenant-lifecycle surface — list, a
// provision dialog, and inline row actions strictly from
// availableTenantActions(status) (TenantStateMachine.java's real transition
// matrix), so the UI never offers an action the backend would refuse with a
// 409. No detail route (this stage is deliberately small — see the stage
// plan's §4): everything a platform admin needs is right here in the list.
import { onMounted, reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  activateTenant,
  availableTenantActions,
  deleteTenant,
  listTenants,
  provisionTenant,
  suspendTenant,
  DEFAULT_PAGE_SIZE,
  type PageView,
  type TenantLifecycleAction,
  type TenantStatus,
  type TenantView,
} from '@/api/platformAdminTenants'

const page = ref(0)
const pageData = ref<PageView<TenantView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listTenants(page.value, DEFAULT_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      // Never actually produced on this surface — parsePlatformResult maps
      // a session-expired outcome to the 'error' arm below instead (there is
      // no silent-navigation path here; see platformAdminClient.ts's header
      // comment). Kept only so the switch is exhaustive over AdminResult<T>.
      break
    default:
      // Covers validationErrors/conflict/error, including a session-expired
      // result: PlatformAdminLayout's own 401 handling on the NEXT guarded
      // navigation is what actually routes the operator back to
      // /platform/login — this banner is the honest interim state.
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)
watch(page, load)

function statusVariant(status: TenantStatus): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'ACTIVE':
      return 'default'
    case 'PROVISIONING':
      return 'secondary'
    case 'SUSPENDED':
      return 'destructive'
    case 'DELETED':
      return 'outline'
  }
}

// ---- provision dialog --------------------------------------------------

const SLUG_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/

const isProvisionOpen = ref(false)
const isProvisioning = ref(false)
const provisionForm = reactive({ slug: '', name: '' })
const provisionErrors = reactive<Record<string, string>>({})
const provisionBanner = ref('')
const provisionSuccessMessage = ref('')

function resetProvisionForm(): void {
  provisionForm.slug = ''
  provisionForm.name = ''
  provisionBanner.value = ''
  for (const key of Object.keys(provisionErrors)) delete provisionErrors[key]
}

function handleProvisionOpenChange(open: boolean): void {
  isProvisionOpen.value = open
  if (!open) resetProvisionForm()
}

async function handleProvision(): Promise<void> {
  if (isProvisioning.value) return
  provisionBanner.value = ''
  for (const key of Object.keys(provisionErrors)) delete provisionErrors[key]

  if (provisionForm.slug && !SLUG_PATTERN.test(provisionForm.slug)) {
    provisionErrors.slug = 'Lowercase alphanumerics and hyphens only, not starting or ending with a hyphen.'
    return
  }

  isProvisioning.value = true
  try {
    const result = await provisionTenant({ slug: provisionForm.slug, name: provisionForm.name })
    switch (result.kind) {
      case 'ok':
        isProvisionOpen.value = false
        provisionSuccessMessage.value =
          `Provisioned "${result.value.slug}". CloseAuth also created its starter-pack roles and scopes, ` +
          `default branding, registration config, and the admin-console-${result.value.slug} client its tenant ` +
          `admins sign in through. The tenant is PROVISIONING — activate it before its users can sign in.`
        resetProvisionForm()
        page.value = 0
        await load()
        break
      case 'validationErrors':
        Object.assign(provisionErrors, result.errors)
        break
      case 'conflict':
        provisionBanner.value = result.code === 'tenant.slug_conflict' ? 'That slug is already in use.' : result.message
        break
      case 'reauth':
        // Never actually produced on this surface — see load()'s identical comment.
        break
      default:
        provisionBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isProvisioning.value = false
  }
}

// ---- row actions ---------------------------------------------------------

const actionPending = ref(false)
const actionError = ref('')
const confirmState = ref<{ tenantId: string; slug: string; action: 'suspend' | 'delete' } | null>(null)

function startAction(tenant: TenantView, action: TenantLifecycleAction): void {
  if (actionPending.value) return
  actionError.value = ''
  if (action === 'suspend' || action === 'delete') {
    confirmState.value = { tenantId: tenant.id, slug: tenant.slug, action }
    return
  }
  void runLifecycle(tenant.id, action)
}

async function runLifecycle(tenantId: string, action: TenantLifecycleAction): Promise<void> {
  if (actionPending.value) return
  actionError.value = ''
  actionPending.value = true
  try {
    const result =
      action === 'activate' ? await activateTenant(tenantId) : action === 'suspend' ? await suspendTenant(tenantId) : await deleteTenant(tenantId)
    switch (result.kind) {
      case 'ok':
        confirmState.value = null
        await load()
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

const confirmTitle = () => (confirmState.value?.action === 'suspend' ? `Suspend ${confirmState.value.slug}?` : `Delete ${confirmState.value?.slug}?`)
const confirmDescription = () =>
  confirmState.value?.action === 'suspend'
    ? "This revokes every user's live access tokens and SSO sessions in this tenant immediately. They are signed out now, not when their tokens expire."
    : 'Soft-delete is terminal: this tenant cannot be reactivated, and its users’ live tokens are revoked immediately.'
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Tenants</h1>
        <p class="text-sm text-muted-foreground">Provision, activate, and suspend tenants across the platform.</p>
      </div>
      <Dialog :open="isProvisionOpen" @update:open="handleProvisionOpenChange">
        <DialogTrigger as-child>
          <Button id="new-tenant-button">Provision tenant</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Provision a tenant</DialogTitle>
            <DialogDescription>
              Creates a tenant in PROVISIONING status along with its starter-pack roles, default branding,
              registration config, and admin-console client.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleProvision">
            <FormField id="new-tenant-slug" label="Slug" :error="provisionErrors.slug" hint="Lowercase alphanumerics and hyphens only.">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-tenant-slug"
                  v-model="provisionForm.slug"
                  type="text"
                  required
                  :disabled="isProvisioning"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <FormField id="new-tenant-name" label="Name" :error="provisionErrors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-tenant-name"
                  v-model="provisionForm.name"
                  type="text"
                  required
                  :disabled="isProvisioning"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <p v-if="provisionBanner" role="alert" class="text-sm text-destructive">{{ provisionBanner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isProvisioning">
                {{ isProvisioning ? 'Provisioning…' : 'Provision' }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <p v-if="provisionSuccessMessage" role="status" class="text-sm rounded-md border border-border bg-muted p-3">
      {{ provisionSuccessMessage }}
    </p>
    <p v-if="actionError" role="alert" class="text-sm text-destructive">{{ actionError }}</p>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Slug</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="5">
                No tenants yet.
              </TableEmpty>
              <TableRow v-for="tenant in pageData?.items ?? []" :key="tenant.id" :data-tenant-id="tenant.id">
                <TableCell class="font-mono text-xs">{{ tenant.slug }}</TableCell>
                <TableCell>{{ tenant.name }}</TableCell>
                <TableCell><Badge :variant="statusVariant(tenant.status)">{{ tenant.status }}</Badge></TableCell>
                <TableCell>{{ new Date(tenant.createdAt).toLocaleDateString() }}</TableCell>
                <TableCell>
                  <div class="flex items-center gap-2">
                    <Button
                      v-for="action in availableTenantActions(tenant.status)"
                      :id="`tenant-action-${tenant.id}-${action}`"
                      :key="action"
                      size="sm"
                      :variant="action === 'delete' || action === 'suspend' ? 'destructive' : 'outline'"
                      :disabled="actionPending"
                      @click="startAction(tenant, action)"
                    >
                      {{ action === 'activate' ? 'Activate' : action === 'suspend' ? 'Suspend' : 'Delete' }}
                    </Button>
                    <span v-if="availableTenantActions(tenant.status).length === 0" class="text-xs text-muted-foreground">
                      No actions (terminal)
                    </span>
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
      :open="confirmState !== null"
      :title="confirmTitle()"
      :description="confirmDescription()"
      :confirm-label="confirmState?.action === 'suspend' ? 'Suspend' : 'Delete'"
      :pending="actionPending"
      @update:open="(open: boolean) => { if (!open) confirmState = null }"
      @confirm="() => confirmState && runLifecycle(confirmState.tenantId, confirmState.action)"
    />
  </div>
</template>
