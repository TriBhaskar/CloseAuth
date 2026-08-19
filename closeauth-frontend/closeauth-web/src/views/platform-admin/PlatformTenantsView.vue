<script setup lang="ts">
// Stage UI-4 / FE-3a / FE-3b: the platform console's tenant list — table,
// provision dialog, and inline row actions strictly from
// availableTenantActions(status) (TenantStateMachine.java's real transition
// matrix), so the UI never offers an action the backend would refuse with a
// 409. Row click navigates to the tenant detail page (FE-3b,
// PlatformTenantDetailView.vue), which shares the same two composables this
// view uses for lifecycle actions and onboarding — see their own header
// comments for why they were extracted.
//
// FE-3b decision #1 (confirmed): the provision->bootstrap chain now
// auto-activates — no intermediate "activate?" consent dialog. Provisioning
// succeeds -> toast -> the tenant is silently activated -> the bootstrap
// dialog opens immediately. If activation itself fails, useTenantOnboarding
// surfaces its own small 'activateFailed' recovery dialog (Retry/Later),
// never a raw error page.
import { computed, defineComponent, h, reactive, ref, watch, type PropType, type VNode } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import TypedConfirmDialog from '@/components/common/TypedConfirmDialog.vue'
import SecretRevealPanel from '@/components/common/SecretRevealPanel.vue'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import StateBadge, { tenantStatusTone } from '@/components/common/StateBadge.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import { describeAdminError } from '@/api/problem'
import { previewTenantId } from '@/lib/tenantIdPreview'
import { useToast } from '@/composables/useToast'
import { useTenantLifecycleActions } from '@/composables/useTenantLifecycleActions'
import { useTenantOnboarding } from '@/composables/useTenantOnboarding'
import {
  availableTenantActions,
  listTenants,
  provisionTenant,
  type PageView,
  type TenantView,
} from '@/api/platformAdminTenants'

const router = useRouter()
const { toast } = useToast()

// FE-3a: this deployment's tenant fleet is expected to be small at this
// stage (an internal, platform-operator-only screen) — loading a large page
// once and filtering client-side (see searchQuery/filteredItems below) is a
// simpler, always-correct alternative to real server-side search, which
// doesn't exist end-to-end today (the Go BFF's pagingQuery whitelists only
// page/size). True server pagination becomes mostly cosmetic at this scale;
// tracked as a note if the fleet ever grows large enough for that tradeoff
// to matter again.
const LIST_PAGE_SIZE = 200

const page = ref(0)
const pageData = ref<PageView<TenantView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const searchQuery = ref('')

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listTenants(page.value, LIST_PAGE_SIZE)
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
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

void load()
watch(page, load)

const hasActiveFilters = computed(() => searchQuery.value.trim().length > 0)

const filteredItems = computed<TenantView[]>(() => {
  const items = pageData.value?.items ?? []
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return items
  return items.filter((t) => t.name.toLowerCase().includes(q) || t.slug.toLowerCase().includes(q))
})

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

function goToTenantDetail(tenant: TenantView): void {
  void router.push({ name: 'platform-admin-tenant-detail', params: { tenantId: tenant.id } })
}

// ---- shared composables (also consumed by PlatformTenantDetailView.vue) ---

const lifecycle = useTenantLifecycleActions(load)
const onboarding = useTenantOnboarding(load)

// ---- provision dialog --------------------------------------------------

const isProvisionOpen = ref(false)
const isProvisioning = ref(false)
const provisionForm = reactive({ name: '' })
const provisionErrors = reactive<Record<string, string>>({})
const provisionBanner = ref('')

const tenantIdPreview = computed(() => previewTenantId(provisionForm.name))

function resetProvisionForm(): void {
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

  isProvisioning.value = true
  try {
    const result = await provisionTenant({ name: provisionForm.name })
    switch (result.kind) {
      case 'ok': {
        toast({ title: 'Tenant provisioned', type: 'success' })
        resetProvisionForm()
        page.value = 0
        await load()
        // FE-3b decision #1: auto-activate, chain straight into bootstrap —
        // the provision dialog stays open (still showing "Provisioning…")
        // through this whole round trip, so there's no flash of nothing
        // between it closing and the bootstrap dialog opening; both state
        // changes land in the same tick.
        await onboarding.activateAndOpenBootstrap(result.value.id, result.value.slug, result.value.name)
        isProvisionOpen.value = false
        break
      }
      case 'validationErrors':
        Object.assign(provisionErrors, result.errors)
        break
      case 'conflict':
        provisionBanner.value =
          result.code === 'tenant.slug_conflict' ? "Couldn't provision that tenant — try again." : result.message
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

// ---- FE-3a: DataTable columns -------------------------------------------

const TenantActionsCell = defineComponent({
  props: { tenant: { type: Object as PropType<TenantView>, required: true } },
  setup(props) {
    return (): VNode => {
      const tenant = props.tenant
      const lifecycleActions = availableTenantActions(tenant.status)
      const onboardingActionKind = onboarding.onboardingAction(tenant)
      const children: VNode[] = []

      for (const action of lifecycleActions) {
        children.push(
          h(
            Button,
            {
              id: `tenant-action-${tenant.id}-${action}`,
              size: 'sm',
              variant: action === 'suspend' || action === 'delete' ? 'destructive' : 'outline',
              disabled: lifecycle.actionPending.value,
              onClick: () => lifecycle.startAction(tenant, action),
            },
            { default: () => (action === 'activate' ? 'Activate' : action === 'suspend' ? 'Suspend' : 'Delete') },
          ),
        )
      }
      if (onboardingActionKind === 'bootstrap') {
        children.push(
          h(
            Button,
            {
              id: `tenant-bootstrap-${tenant.id}`,
              size: 'sm',
              variant: 'outline',
              disabled: lifecycle.actionPending.value,
              onClick: () => onboarding.openBootstrapForm(tenant.id, tenant.slug),
            },
            { default: () => 'Add first admin' },
          ),
        )
      } else if (onboardingActionKind === 'reissue') {
        children.push(
          h(
            Button,
            {
              id: `tenant-reissue-${tenant.id}`,
              size: 'sm',
              variant: 'outline',
              disabled: lifecycle.actionPending.value,
              onClick: () => onboarding.openReissuePicker(tenant.id, tenant.slug),
            },
            { default: () => 'Reissue credential' },
          ),
        )
      }

      const rowChildren: VNode[] = [h('div', { class: 'flex flex-wrap items-center gap-2' }, children)]
      if (children.length === 0) {
        rowChildren[0] = h('span', { class: 'text-xs text-muted-foreground' }, 'No actions (terminal)')
      }
      if (tenant.status === 'PROVISIONING' || tenant.status === 'SUSPENDED') {
        rowChildren.push(h('span', { class: 'text-xs text-muted-foreground' }, 'Activate to add an admin'))
      }
      // Row click navigates to the detail page (onRowClick, below) — this
      // cell's own buttons must never also trigger that navigation.
      return h('div', { class: 'flex flex-col gap-2', onClick: (e: MouseEvent) => e.stopPropagation() }, rowChildren)
    }
  },
})

const columns = computed<ColumnDef<TenantView, unknown>[]>(() => [
  {
    id: 'tenantId',
    header: 'Tenant ID',
    cell: ({ row }) =>
      h('div', { class: 'flex flex-col gap-1' }, [
        h(IdentifierChip, { kind: 'tenant', value: row.original.slug }),
        h('span', { class: 'text-[0.75rem] text-muted-foreground' }, row.original.name),
      ]),
  },
  {
    id: 'name',
    header: 'Name',
    cell: ({ row }) => row.original.name,
  },
  {
    id: 'status',
    header: 'Status',
    cell: ({ row }) => h(StateBadge, { tone: tenantStatusTone(row.original.status), label: row.original.status }),
  },
  {
    id: 'admins',
    header: 'Admins',
    cell: ({ row }) => {
      const tenant = row.original
      if (tenant.adminCount === null) return h('span', { class: 'text-xs text-muted-foreground' }, '—')
      if (tenant.adminCount === 0) {
        return h('div', { class: 'flex items-center gap-2' }, [
          h('span', tenant.adminCount),
          h(StateBadge, {
            tone: 'warn',
            label: 'Incomplete',
            title: 'No tenant admin has been created yet.',
          }),
        ])
      }
      return h('span', tenant.adminCount)
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
    cell: ({ row }) => h(TenantActionsCell, { tenant: row.original }),
  },
])
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Tenants</h1>
        <p class="text-sm text-muted-foreground">Every workspace on this deployment.</p>
      </div>
      <Dialog :open="isProvisionOpen" @update:open="handleProvisionOpenChange">
        <DialogTrigger as-child>
          <Button id="new-tenant-button">Provision tenant</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Provision a tenant</DialogTitle>
            <DialogDescription>
              Creates a tenant in PROVISIONING status with starter roles, default branding, registration config, and
              admin-only registration.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleProvision">
            <FormField id="new-tenant-name" label="Tenant name" :error="provisionErrors.name">
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
            <p id="new-tenant-id-preview" class="text-xs font-mono text-muted-foreground">
              <template v-if="tenantIdPreview">Tenant ID will be {{ tenantIdPreview }} · can't be changed later</template>
              <template v-else>Tenant ID will be generated automatically.</template>
            </p>

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

    <p v-if="lifecycle.actionError.value" role="alert" class="text-sm text-destructive">{{ lifecycle.actionError.value }}</p>

    <DataTable
      :columns="columns"
      :data="filteredItems"
      :row-key="(t: TenantView) => t.id"
      :state="dataTableState"
      :row-attrs="(t: TenantView) => ({ 'data-tenant-id': t.id })"
      :on-row-click="goToTenantDetail"
      :page="pageData?.page ?? 0"
      :size="pageData?.size ?? LIST_PAGE_SIZE"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      :has-active-filters="hasActiveFilters"
      empty-title="No tenants yet."
      empty-description="Provision one to get started."
      filtered-empty-title="No tenants match that search."
      filtered-empty-description="Try a different name or Tenant ID."
      search-placeholder="Search tenants…"
      @update:page="(p: number) => (page = p)"
      @update:search="(q: string) => (searchQuery = q)"
      @retry="load"
    >
      <template #action>
        <Button type="button" @click="isProvisionOpen = true">Provision tenant</Button>
      </template>
    </DataTable>

    <!-- FE-3a (spec §6.3.5): suspend/activate share ConfirmDialog; delete
         requires the exact Tenant ID typed (TypedConfirmDialog). -->
    <ConfirmDialog
      :open="lifecycle.isConfirmDialogOpen.value"
      :title="lifecycle.confirmTitle.value"
      :description="lifecycle.confirmDescription.value"
      :confirm-label="lifecycle.confirmState.value?.action === 'suspend' ? 'Suspend' : 'Activate'"
      :destructive="lifecycle.confirmState.value?.action === 'suspend'"
      :pending="lifecycle.actionPending.value"
      @update:open="(open: boolean) => { if (!open) lifecycle.cancelAction() }"
      @confirm="lifecycle.confirmAction"
    />
    <TypedConfirmDialog
      :open="lifecycle.isDeleteDialogOpen.value"
      :title="`Delete ${lifecycle.confirmState.value?.name}?`"
      description="Soft-delete is terminal: this tenant cannot be reactivated, and its users’ live tokens are revoked immediately."
      :match-text="lifecycle.confirmState.value?.slug ?? ''"
      match-label="Type the Tenant ID to confirm"
      confirm-label="Delete"
      :pending="lifecycle.actionPending.value"
      @update:open="(open: boolean) => { if (!open) lifecycle.cancelAction() }"
      @confirm="lifecycle.confirmAction"
    />

    <!-- FE-3b: bootstrap form / reissue picker / activate-failed recovery —
         one Dialog, body keyed by onboardingState.kind. The success hand-off
         is a SEPARATE, standalone SecretRevealPanel below — never nested in
         this Dialog (see useTenantOnboarding.ts's header comment). -->
    <Dialog :open="onboarding.onboardingState.value !== null" @update:open="(open: boolean) => { if (!open) onboarding.closeOnboarding() }">
      <DialogContent>
        <template v-if="onboarding.onboardingState.value?.kind === 'activateFailed'">
          <DialogHeader>
            <DialogTitle>Couldn't activate {{ onboarding.onboardingState.value.name }}</DialogTitle>
            <DialogDescription>{{ onboarding.onboardingState.value.message }}</DialogDescription>
          </DialogHeader>
          <DialogFooter class="flex-col sm:flex-row gap-2">
            <Button id="onboarding-activate-later" type="button" variant="outline" @click="onboarding.closeOnboarding()">
              Later
            </Button>
            <Button id="onboarding-activate-retry" type="button" :disabled="onboarding.activatePending.value" @click="onboarding.retryActivate()">
              {{ onboarding.activatePending.value ? 'Retrying…' : 'Retry' }}
            </Button>
          </DialogFooter>
        </template>

        <template v-else-if="onboarding.onboardingState.value?.kind === 'bootstrapForm'">
          <DialogHeader>
            <DialogTitle>Add {{ onboarding.onboardingState.value.slug }}'s first admin</DialogTitle>
            <DialogDescription>
              CloseAuth already created this tenant's starter-pack roles and scopes, default branding, registration
              config, and the admin-console-{{ onboarding.onboardingState.value.slug }} client its admins sign in
              through. This address receives the onboarding link and becomes a TENANT_ADMIN for this tenant. It
              cannot be changed afterwards — reissuing a credential only re-sends to this same address.
            </DialogDescription>
          </DialogHeader>
          <form
            id="bootstrap-admin-form"
            class="flex flex-col gap-4"
            novalidate
            @submit.prevent="onboarding.handleBootstrap"
          >
            <FormField id="bootstrap-admin-email" label="Email" :error="onboarding.bootstrapErrors.email">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="bootstrap-admin-email"
                  v-model="onboarding.bootstrapForm.email"
                  type="email"
                  autocomplete="email"
                  required
                  :disabled="onboarding.isBootstrapping.value"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <FormField id="bootstrap-admin-confirm-email" label="Confirm email" :error="onboarding.bootstrapErrors.confirmEmail">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="bootstrap-admin-confirm-email"
                  v-model="onboarding.bootstrapForm.confirmEmail"
                  type="email"
                  autocomplete="off"
                  required
                  :disabled="onboarding.isBootstrapping.value"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <div class="grid grid-cols-2 gap-3">
              <FormField id="bootstrap-admin-first-name" label="First name" :error="onboarding.bootstrapErrors.firstName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="bootstrap-admin-first-name"
                    v-model="onboarding.bootstrapForm.firstName"
                    type="text"
                    autocomplete="given-name"
                    :disabled="onboarding.isBootstrapping.value"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
              <FormField id="bootstrap-admin-last-name" label="Last name" :error="onboarding.bootstrapErrors.lastName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="bootstrap-admin-last-name"
                    v-model="onboarding.bootstrapForm.lastName"
                    type="text"
                    autocomplete="family-name"
                    :disabled="onboarding.isBootstrapping.value"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
            </div>

            <p v-if="onboarding.bootstrapBanner.value" role="alert" class="text-sm text-destructive">{{ onboarding.bootstrapBanner.value }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="onboarding.isBootstrapping.value">
                {{ onboarding.isBootstrapping.value ? 'Creating…' : 'Create first admin' }}
              </Button>
            </DialogFooter>
          </form>
        </template>

        <template v-else-if="onboarding.onboardingState.value?.kind === 'reissuePicker'">
          <DialogHeader>
            <DialogTitle>Reissue an onboarding credential</DialogTitle>
            <DialogDescription>
              Choose the admin to reissue for — this lists every user in "{{ onboarding.onboardingState.value.slug }}",
              not filtered to admins (role isn't part of this read). Reissuing re-sends a fresh link and temporary
              password to that user's existing email; it's refused if they already set their own password.
            </DialogDescription>
          </DialogHeader>
          <p v-if="onboarding.reissueError.value" role="alert" class="text-sm text-destructive">{{ onboarding.reissueError.value }}</p>
          <div v-if="onboarding.reissueLoading.value" class="text-sm text-muted-foreground">Loading users…</div>
          <div v-else-if="onboarding.reissueUsers.value && onboarding.reissueUsers.value.length === 0" class="text-sm text-muted-foreground">
            This tenant has no users yet.
          </div>
          <ul v-else-if="onboarding.reissueUsers.value" class="flex flex-col gap-2 max-h-72 overflow-y-auto">
            <li
              v-for="user in onboarding.reissueUsers.value"
              :key="user.id"
              class="flex items-center justify-between gap-2 rounded-md border border-border p-2"
            >
              <span class="text-sm">{{ user.email }}</span>
              <Button
                :id="`reissue-select-${user.id}`"
                size="sm"
                variant="outline"
                :disabled="onboarding.reissuePending.value"
                @click="onboarding.confirmReissue(user.id, user.email)"
              >
                Reissue
              </Button>
            </li>
          </ul>
          <DialogFooter>
            <Button id="onboarding-reissue-cancel" type="button" variant="outline" @click="onboarding.closeOnboarding()">
              Cancel
            </Button>
          </DialogFooter>
        </template>
      </DialogContent>
    </Dialog>

    <!-- FE-3b: the temp-password / Tenant ID / sign-in URL hand-off — a
         standalone SecretRevealPanel, deliberately never nested in the
         Dialog above. -->
    <SecretRevealPanel
      :open="onboarding.successPanel.value !== null"
      :title="`First admin created — ${onboarding.successPanel.value?.email ?? ''}`"
      warning-message="An onboarding link was already emailed to this address — that's the intended path, nothing else is needed. The temporary password below is only a fallback, shown ONE TIME ONLY: it cannot be retrieved again after you leave this dialog. Share it over a channel you trust — it expires as noted below, and they'll be required to choose their own password before they can sign in."
      :fields="onboarding.successPanelFields.value"
      @continue="onboarding.closeSuccessPanel()"
    />
  </div>
</template>
