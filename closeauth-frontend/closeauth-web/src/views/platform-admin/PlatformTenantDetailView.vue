<script setup lang="ts">
// FE-3b (spec §6.3.3): the tenant detail page — header, Admins, Lifecycle,
// and a Danger Zone (delete only, kept structurally separate from Lifecycle
// per spec's own section split). Shares useTenantLifecycleActions and
// useTenantOnboarding with PlatformTenantsView.vue (the list) — see those
// composables' own header comments for why they're extracted rather than
// duplicated. The dialog/panel TEMPLATE markup itself (bootstrap form,
// reissue picker, activate-failed recovery, the SecretRevealPanel hand-off)
// is intentionally duplicated here rather than also extracted into a shared
// component — this session only committed to extracting the LOGIC; a third,
// template-level extraction is a reasonable next step once real drift (not
// just line-count) shows up between the two, not before.
//
// Admins section (decision, confirmed): reuses the existing undifferentiated
// all-users reissue picker, not a new backend endpoint — the real
// per-admin-holder list is a tracked, named backend dependency (see the
// plan's own ground truth). What IS reliable here is adminCount itself.
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import TypedConfirmDialog from '@/components/common/TypedConfirmDialog.vue'
import SecretRevealPanel from '@/components/common/SecretRevealPanel.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import StateBadge, { tenantStatusTone } from '@/components/common/StateBadge.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CopyButton from '@/components/common/CopyButton.vue'
import { describeAdminError } from '@/api/problem'
import { tenantSignInUrl } from '@/lib/tenantSignInUrl'
import { useTenantLifecycleActions } from '@/composables/useTenantLifecycleActions'
import { useTenantOnboarding } from '@/composables/useTenantOnboarding'
import { availableTenantActions, getTenant, type TenantLifecycleAction, type TenantView } from '@/api/platformAdminTenants'

const route = useRoute()
const router = useRouter()
const tenantId = String(route.params.tenantId ?? '')

const tenant = ref<TenantView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getTenant(tenantId)
  switch (result.kind) {
    case 'ok':
      tenant.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      tenant.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)

function backToList(): void {
  void router.push({ name: 'platform-admin-tenants' })
}

// Delete leaves nothing on this page to show — go back to the list.
// Every other lifecycle action just re-fetches this one tenant in place.
const lifecycle = useTenantLifecycleActions((action: TenantLifecycleAction) => {
  if (action === 'delete') {
    backToList()
    return
  }
  return load()
})
const onboarding = useTenantOnboarding(load)

const signInUrl = computed(() => (tenant.value ? tenantSignInUrl(tenant.value.slug) : ''))

// Lifecycle section (spec §6.3.3) is deliberately just suspend/activate —
// delete lives in its own Danger Zone section below, matching spec's own
// two-section split (not the list row's compact everything-inline layout).
const lifecycleTransitions = computed(() =>
  tenant.value ? availableTenantActions(tenant.value.status).filter((a) => a !== 'delete') : [],
)
const canDelete = computed(() => (tenant.value ? availableTenantActions(tenant.value.status).includes('delete') : false))

function transitionEffect(action: TenantLifecycleAction): string {
  return action === 'suspend'
    ? "Users won't be able to sign in and all active tokens stop working immediately."
    : 'This tenant becomes ACTIVE. Its users (once it has any) will be able to sign in.'
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToList">&larr; Back to tenants</Button>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div v-if="tenant" class="flex flex-col gap-6">
        <!-- Header -->
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <h1 id="tenant-detail-name" class="text-xl font-semibold tracking-tight">{{ tenant.name }}</h1>
              <div class="flex items-center gap-2 mt-1">
                <IdentifierChip kind="tenant" :value="tenant.slug" />
                <RelativeTime :value="tenant.createdAt" />
              </div>
            </div>
            <StateBadge :tone="tenantStatusTone(tenant.status)" :label="tenant.status" />
          </div>

          <div class="flex items-center gap-2">
            <code id="tenant-detail-signin-url" class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all">
              {{ signInUrl }}
            </code>
            <CopyButton
              :value="signInUrl"
              class="inline-flex items-center justify-center rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-xs hover:bg-accent"
            />
          </div>
        </div>

        <!-- Admins -->
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Admins</h2>

          <div v-if="tenant.adminCount === null" class="text-sm text-muted-foreground">—</div>
          <div v-else class="flex items-center gap-2">
            <span class="text-sm">{{ tenant.adminCount }} admin{{ tenant.adminCount === 1 ? '' : 's' }}</span>
            <StateBadge
              v-if="tenant.status === 'ACTIVE' && tenant.adminCount === 0"
              tone="warn"
              label="Incomplete"
              title="No tenant admin has been created yet."
            />
          </div>

          <Button
            v-if="onboarding.onboardingAction(tenant) === 'bootstrap'"
            id="tenant-detail-bootstrap"
            size="sm"
            variant="outline"
            class="self-start"
            @click="onboarding.openBootstrapForm(tenant.id, tenant.slug)"
          >
            Bootstrap admin
          </Button>
          <Button
            v-else-if="onboarding.onboardingAction(tenant) === 'reissue'"
            id="tenant-detail-reissue"
            size="sm"
            variant="outline"
            class="self-start"
            @click="onboarding.openReissuePicker(tenant.id, tenant.slug)"
          >
            Reissue a credential
          </Button>
          <p v-if="tenant.status !== 'ACTIVE'" class="text-xs text-muted-foreground">Activate this tenant to add an admin.</p>
        </div>

        <!-- Lifecycle: spelled-out effect per transition (spec §6.3.3), suspend/activate only — delete lives in Danger Zone. -->
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Lifecycle</h2>
          <p v-if="lifecycleTransitions.length === 0" class="text-sm text-muted-foreground">No transitions available — this tenant is terminal.</p>
          <div v-for="action in lifecycleTransitions" :key="action" class="flex items-center justify-between gap-4 rounded-md border border-border p-3">
            <p class="text-sm text-muted-foreground">{{ transitionEffect(action) }}</p>
            <Button
              :id="`tenant-detail-action-${action}`"
              size="sm"
              :variant="action === 'suspend' ? 'destructive' : 'outline'"
              :disabled="lifecycle.actionPending.value"
              @click="lifecycle.startAction(tenant, action)"
            >
              {{ action === 'activate' ? 'Activate' : 'Suspend' }}
            </Button>
          </div>
          <p v-if="lifecycle.actionError.value" role="alert" class="text-sm text-destructive">{{ lifecycle.actionError.value }}</p>
        </div>

        <!-- Danger Zone -->
        <div v-if="canDelete" class="rounded-xl border border-danger bg-danger-wash p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight text-danger">Danger zone</h2>
          <p class="text-sm text-muted-foreground">
            Soft-delete is terminal: this tenant cannot be reactivated, and its users’ live tokens are revoked immediately.
          </p>
          <Button
            id="tenant-detail-delete"
            variant="destructive"
            class="self-start"
            :disabled="lifecycle.actionPending.value"
            @click="lifecycle.startAction(tenant, 'delete')"
          >
            Delete tenant
          </Button>
        </div>
      </div>
    </QueryState>

    <!-- Lifecycle confirm dialogs — shared composable, same shape as the list view. -->
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

    <!-- Onboarding: bootstrap form / reissue picker / activate-failed recovery. -->
    <Dialog :open="onboarding.onboardingState.value !== null" @update:open="(open: boolean) => { if (!open) onboarding.closeOnboarding() }">
      <DialogContent>
        <template v-if="onboarding.onboardingState.value?.kind === 'activateFailed'">
          <DialogHeader>
            <DialogTitle>Couldn't activate {{ onboarding.onboardingState.value.name }}</DialogTitle>
            <DialogDescription>{{ onboarding.onboardingState.value.message }}</DialogDescription>
          </DialogHeader>
          <DialogFooter class="flex-col sm:flex-row gap-2">
            <Button id="tenant-detail-activate-later" type="button" variant="outline" @click="onboarding.closeOnboarding()">
              Later
            </Button>
            <Button id="tenant-detail-activate-retry" type="button" :disabled="onboarding.activatePending.value" @click="onboarding.retryActivate()">
              {{ onboarding.activatePending.value ? 'Retrying…' : 'Retry' }}
            </Button>
          </DialogFooter>
        </template>

        <template v-else-if="onboarding.onboardingState.value?.kind === 'bootstrapForm'">
          <DialogHeader>
            <DialogTitle>Add {{ onboarding.onboardingState.value.slug }}'s first admin</DialogTitle>
            <DialogDescription>
              This address receives the onboarding link and becomes a TENANT_ADMIN for this tenant. It cannot be
              changed afterwards — reissuing a credential only re-sends to this same address.
            </DialogDescription>
          </DialogHeader>
          <form
            id="tenant-detail-bootstrap-form"
            class="flex flex-col gap-4"
            novalidate
            @submit.prevent="onboarding.handleBootstrap"
          >
            <FormField id="tenant-detail-bootstrap-email" label="Email" :error="onboarding.bootstrapErrors.email">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="tenant-detail-bootstrap-email"
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
            <FormField id="tenant-detail-bootstrap-confirm-email" label="Confirm email" :error="onboarding.bootstrapErrors.confirmEmail">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="tenant-detail-bootstrap-confirm-email"
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
              <FormField id="tenant-detail-bootstrap-first-name" label="First name" :error="onboarding.bootstrapErrors.firstName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="tenant-detail-bootstrap-first-name"
                    v-model="onboarding.bootstrapForm.firstName"
                    type="text"
                    autocomplete="given-name"
                    :disabled="onboarding.isBootstrapping.value"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
              <FormField id="tenant-detail-bootstrap-last-name" label="Last name" :error="onboarding.bootstrapErrors.lastName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="tenant-detail-bootstrap-last-name"
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
                :id="`tenant-detail-reissue-select-${user.id}`"
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
            <Button id="tenant-detail-reissue-cancel" type="button" variant="outline" @click="onboarding.closeOnboarding()">
              Cancel
            </Button>
          </DialogFooter>
        </template>
      </DialogContent>
    </Dialog>

    <SecretRevealPanel
      :open="onboarding.successPanel.value !== null"
      :title="`First admin created — ${onboarding.successPanel.value?.email ?? ''}`"
      warning-message="An onboarding link was already emailed to this address — that's the intended path, nothing else is needed. The temporary password below is only a fallback, shown ONE TIME ONLY: it cannot be retrieved again after you leave this dialog. Share it over a channel you trust — it expires as noted below, and they'll be required to choose their own password before they can sign in."
      :fields="onboarding.successPanelFields.value"
      @continue="onboarding.closeSuccessPanel()"
    />
  </div>
</template>
