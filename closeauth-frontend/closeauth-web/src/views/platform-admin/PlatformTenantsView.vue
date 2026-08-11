<script setup lang="ts">
// Stage UI-4: the platform console's tenant-lifecycle surface — list, a
// provision dialog, and inline row actions strictly from
// availableTenantActions(status) (TenantStateMachine.java's real transition
// matrix), so the UI never offers an action the backend would refuse with a
// 409. No detail route (this stage is deliberately small — see the stage
// plan's §4): everything a platform admin needs is right here in the list.
//
// Stage UI-4b adds the tenant-onboarding surface this console existed to
// reach: a guided post-provision handoff into bootstrapping a tenant's first
// admin, an "incomplete tenant" flag driven by adminCount, and a reissue
// path for an unused onboarding credential — all as ONE dialog
// (onboardingState below) whose body switches on a discriminated-union
// step, rather than a fourth separate Dialog component. Two load-bearing
// decisions:
//   - bootstrap-admin requires the tenant to be ACTIVE (TenantService.
//     requireActiveTenant refuses PROVISIONING/SUSPENDED with 403
//     tenant.not_active) — provisioning alone leaves a tenant PROVISIONING,
//     so the guided flow inserts an explicit, consented activation step
//     rather than chaining silently (which would also violate decision 10:
//     activation stays a distinct step from bootstrap-admin).
//   - the one-time temporaryPassword is NOT rendered by default (§2.2.4:
//     the emailed link is the primary path; every read of the fallback
//     password exercises real residual risk) — it sits behind a collapsed
//     disclosure, masked, reveal-on-request, same mask/reveal/copy
//     mechanics as TenantClientCredentialsView.vue's write-once secret
//     handoff, gated behind an acknowledgement checkbox before Done closes
//     the dialog.
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
import {
  bootstrapTenantAdmin,
  listTenantUsers,
  reissueOnboardingCredential,
  type TenantUserView,
} from '@/api/platformAdminTenantUsers'

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
        // Stage UI-4b: lead into bootstrapping this tenant's first admin,
        // but don't force it — decision 9 (guided, non-blocking).
        onboardingState.value = { kind: 'activatePrompt', tenantId: result.value.id, slug: result.value.slug }
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

// ---- Stage UI-4b: tenant onboarding (bootstrap first admin / reissue) ----

// What onboarding action, if any, this tenant's row should offer. Kept
// separate from availableTenantActions (the pure lifecycle transition
// matrix) — onboarding is derived from status AND adminCount, not a state
// machine transition the backend would 409 on.
function onboardingAction(tenant: TenantView): 'bootstrap' | 'reissue' | null {
  if (tenant.status !== 'ACTIVE' || tenant.adminCount === null) return null
  return tenant.adminCount === 0 ? 'bootstrap' : 'reissue'
}

type OnboardingState =
  | { kind: 'activatePrompt'; tenantId: string; slug: string }
  | { kind: 'bootstrapForm'; tenantId: string; slug: string }
  | { kind: 'reissuePicker'; tenantId: string; slug: string }
  | { kind: 'success'; email: string; temporaryPassword: string; temporaryPasswordExpiresAt: string }

const onboardingState = ref<OnboardingState | null>(null)

/**
 * The platform session is access-only, 5 minutes, never silently renewed
 * (RequirePlatformSession has no reauth branch). A 401 mid-wizard is a real
 * state the operator must recover from without guessing whether their write
 * landed — see this file's header comment and the plan's §4. `step`
 * distinguishes what's actually ambiguous: activation is idempotent-ish
 * (retriable safely), but bootstrap-admin's own write outcome is genuinely
 * unknown until the row is re-checked — that's why the adminCount flag
 * (onboardingAction above) isn't just cosmetic, it's the recovery mechanism.
 */
function sessionExpiredCopy(slug: string, step: 'activate' | 'bootstrap' | 'reissue'): string {
  const created = `Your platform session expired. The tenant "${slug}" was created and is listed — nothing was lost.`
  switch (step) {
    case 'activate':
      return `${created} Sign in again: if it's still PROVISIONING, activate it; if it's already ACTIVE, use "Add first admin" directly.`
    case 'bootstrap':
      return (
        `${created} This write's outcome is uncertain, though — sign in again and check the row: ` +
        `"No admin" means retry "Add first admin"; an admin already showing means check whether the ` +
        `onboarding email arrived before reaching for the temporary password.`
      )
    case 'reissue':
      return `${created} Sign in again and use "Reissue credential" on this admin to retry.`
  }
}

function closeOnboarding(): void {
  onboardingState.value = null
  void load()
}

function handleOnboardingOpenChange(open: boolean): void {
  if (open) return
  // Block ESC/overlay dismissal of an unseen one-time credential — Done
  // (gated behind the acknowledgement checkbox) is the only way off this step.
  if (onboardingState.value?.kind === 'success' && !passwordAcknowledged.value) return
  closeOnboarding()
}

// ---- activate-then-bootstrap prompt ---------------------------------

const activatePromptPending = ref(false)
const activatePromptError = ref('')

function openBootstrapForm(tenantId: string, slug: string): void {
  resetBootstrapForm()
  onboardingState.value = { kind: 'bootstrapForm', tenantId, slug }
}

async function activateAndBootstrap(tenantId: string, slug: string): Promise<void> {
  if (activatePromptPending.value) return
  activatePromptError.value = ''
  activatePromptPending.value = true
  try {
    const result = await activateTenant(tenantId)
    switch (result.kind) {
      case 'ok':
        await load()
        openBootstrapForm(tenantId, slug)
        break
      case 'reauth':
        break
      default:
        activatePromptError.value = result.code === 'session_expired' ? sessionExpiredCopy(slug, 'activate') : describeAdminError(result)
        break
    }
  } finally {
    activatePromptPending.value = false
  }
}

async function activateOnly(tenantId: string, slug: string): Promise<void> {
  if (activatePromptPending.value) return
  activatePromptError.value = ''
  activatePromptPending.value = true
  try {
    const result = await activateTenant(tenantId)
    switch (result.kind) {
      case 'ok':
        closeOnboarding()
        break
      case 'reauth':
        break
      default:
        activatePromptError.value = result.code === 'session_expired' ? sessionExpiredCopy(slug, 'activate') : describeAdminError(result)
        break
    }
  } finally {
    activatePromptPending.value = false
  }
}

// ---- bootstrap-admin form ---------------------------------------------

const bootstrapForm = reactive({ email: '', confirmEmail: '', firstName: '', lastName: '' })
const bootstrapErrors = reactive<Record<string, string>>({})
const bootstrapBanner = ref('')
const isBootstrapping = ref(false)

function resetBootstrapForm(): void {
  bootstrapForm.email = ''
  bootstrapForm.confirmEmail = ''
  bootstrapForm.firstName = ''
  bootstrapForm.lastName = ''
  bootstrapBanner.value = ''
  for (const key of Object.keys(bootstrapErrors)) delete bootstrapErrors[key]
}

async function handleBootstrap(tenantId: string, slug: string): Promise<void> {
  if (isBootstrapping.value) return
  bootstrapBanner.value = ''
  for (const key of Object.keys(bootstrapErrors)) delete bootstrapErrors[key]

  // The only in-phase mitigation for a typo'd admin email: reissue cannot
  // change it later (it re-sends to the same address it already has), so
  // this is the one chance to catch a mismatch before it's unrecoverable.
  if (bootstrapForm.email !== bootstrapForm.confirmEmail) {
    bootstrapErrors.confirmEmail = 'This does not match the email above.'
    return
  }

  isBootstrapping.value = true
  try {
    const result = await bootstrapTenantAdmin(tenantId, {
      email: bootstrapForm.email,
      firstName: bootstrapForm.firstName || undefined,
      lastName: bootstrapForm.lastName || undefined,
    })
    switch (result.kind) {
      case 'ok':
        showSuccess(result.value.user.email, result.value.temporaryPassword, result.value.temporaryPasswordExpiresAt)
        void load()
        break
      case 'validationErrors':
        Object.assign(bootstrapErrors, result.errors)
        break
      case 'conflict':
        bootstrapBanner.value =
          result.code === 'tenant_onboarding.admin_already_exists'
            ? 'This tenant already has an active admin. Use "Reissue credential" on its row if their onboarding link was never used.'
            : result.code === 'user.email_conflict'
              ? 'A user with this email already exists in this tenant.'
              : result.message
        break
      case 'reauth':
        break
      default:
        bootstrapBanner.value =
          result.code === 'session_expired'
            ? sessionExpiredCopy(slug, 'bootstrap')
            : result.code === 'tenant.not_active'
              ? 'This tenant is not ACTIVE. Activate it first, then add its admin.'
              : describeAdminError(result)
        break
    }
  } finally {
    isBootstrapping.value = false
  }
}

// ---- reissue picker -----------------------------------------------------

const reissueUsers = ref<TenantUserView[] | null>(null)
const reissueLoading = ref(false)
const reissueError = ref('')
const reissuePending = ref(false)

async function openReissuePicker(tenantId: string, slug: string): Promise<void> {
  reissueUsers.value = null
  reissueError.value = ''
  onboardingState.value = { kind: 'reissuePicker', tenantId, slug }
  reissueLoading.value = true
  const result = await listTenantUsers(tenantId, 0, 100)
  reissueLoading.value = false
  switch (result.kind) {
    case 'ok':
      reissueUsers.value = result.value.items
      break
    case 'reauth':
      break
    default:
      reissueError.value = result.code === 'session_expired' ? sessionExpiredCopy(slug, 'reissue') : describeAdminError(result)
      break
  }
}

async function confirmReissue(tenantId: string, slug: string, userId: string, userEmail: string): Promise<void> {
  if (reissuePending.value) return
  reissueError.value = ''
  reissuePending.value = true
  try {
    const result = await reissueOnboardingCredential(tenantId, userId)
    switch (result.kind) {
      case 'ok':
        showSuccess(userEmail, result.value.temporaryPassword, result.value.temporaryPasswordExpiresAt)
        void load()
        break
      case 'conflict':
        reissueError.value =
          result.code === 'tenant_onboarding.no_pending_temp_credential'
            ? 'This admin has already set their own password, so there is no onboarding credential to reissue. They should use password reset instead.'
            : result.message
        break
      case 'reauth':
        break
      default:
        reissueError.value = result.code === 'session_expired' ? sessionExpiredCopy(slug, 'reissue') : describeAdminError(result)
        break
    }
  } finally {
    reissuePending.value = false
  }
}

// ---- one-time password panel --------------------------------------------

const disclosureOpen = ref(false)
const secretRevealed = ref(false)
const passwordAcknowledged = ref(false)
const passwordCopied = ref(false)

function showSuccess(email: string, temporaryPassword: string, temporaryPasswordExpiresAt: string): void {
  disclosureOpen.value = false
  secretRevealed.value = false
  passwordAcknowledged.value = false
  passwordCopied.value = false
  onboardingState.value = { kind: 'success', email, temporaryPassword, temporaryPasswordExpiresAt }
}

function maskedSecret(secret: string): string {
  return '•'.repeat(Math.min(secret.length, 32))
}

async function copyPassword(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value)
    passwordCopied.value = true
    setTimeout(() => {
      passwordCopied.value = false
    }, 2000)
  } catch {
    // Clipboard access can be denied/unavailable — the value is still
    // selectable/visible on screen once revealed, so this is a nicety.
  }
}

function acknowledgeAndClose(): void {
  if (!passwordAcknowledged.value) return
  closeOnboarding()
}
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
                <TableHead>Admins</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="6">
                No tenants yet.
              </TableEmpty>
              <TableRow v-for="tenant in pageData?.items ?? []" :key="tenant.id" :data-tenant-id="tenant.id">
                <TableCell class="font-mono text-xs">{{ tenant.slug }}</TableCell>
                <TableCell>{{ tenant.name }}</TableCell>
                <TableCell><Badge :variant="statusVariant(tenant.status)">{{ tenant.status }}</Badge></TableCell>
                <TableCell>
                  <span v-if="tenant.adminCount === null" class="text-xs text-muted-foreground">—</span>
                  <div v-else class="flex items-center gap-2">
                    <span>{{ tenant.adminCount }}</span>
                    <Badge v-if="tenant.status === 'ACTIVE' && tenant.adminCount === 0" variant="destructive">No admin</Badge>
                  </div>
                </TableCell>
                <TableCell>{{ new Date(tenant.createdAt).toLocaleDateString() }}</TableCell>
                <TableCell>
                  <div class="flex flex-col gap-2">
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
                      <Button
                        v-if="onboardingAction(tenant) === 'bootstrap'"
                        :id="`tenant-bootstrap-${tenant.id}`"
                        size="sm"
                        variant="outline"
                        :disabled="actionPending"
                        @click="openBootstrapForm(tenant.id, tenant.slug)"
                      >
                        Add first admin
                      </Button>
                      <Button
                        v-else-if="onboardingAction(tenant) === 'reissue'"
                        :id="`tenant-reissue-${tenant.id}`"
                        size="sm"
                        variant="outline"
                        :disabled="actionPending"
                        @click="openReissuePicker(tenant.id, tenant.slug)"
                      >
                        Reissue credential
                      </Button>
                      <span
                        v-if="availableTenantActions(tenant.status).length === 0 && onboardingAction(tenant) === null && tenant.status === 'DELETED'"
                        class="text-xs text-muted-foreground"
                      >
                        No actions (terminal)
                      </span>
                    </div>
                    <span v-if="tenant.status === 'PROVISIONING' || tenant.status === 'SUSPENDED'" class="text-xs text-muted-foreground">
                      Activate to add an admin
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

    <!-- Stage UI-4b: the guided onboarding dialog — one Dialog, body keyed by onboardingState.kind. -->
    <Dialog :open="onboardingState !== null" @update:open="handleOnboardingOpenChange">
      <DialogContent>
        <template v-if="onboardingState?.kind === 'activatePrompt'">
          <DialogHeader>
            <DialogTitle>Activate "{{ onboardingState!.slug }}" to add its first admin?</DialogTitle>
            <DialogDescription>
              A tenant must be ACTIVE before it can have an admin. Activate now and continue straight to adding the
              first admin, activate without continuing, or come back to this later — any ACTIVE tenant with no
              admin stays flagged in the list.
            </DialogDescription>
          </DialogHeader>
          <p v-if="activatePromptError" role="alert" class="text-sm text-destructive">{{ activatePromptError }}</p>
          <DialogFooter class="flex-col sm:flex-row gap-2">
            <Button id="onboarding-later" type="button" variant="outline" :disabled="activatePromptPending" @click="closeOnboarding">
              Later
            </Button>
            <Button
              id="onboarding-activate-only"
              type="button"
              variant="outline"
              :disabled="activatePromptPending"
              @click="activateOnly(onboardingState!.tenantId, onboardingState!.slug)"
            >
              Activate only
            </Button>
            <Button
              id="onboarding-activate-and-bootstrap"
              type="button"
              :disabled="activatePromptPending"
              @click="activateAndBootstrap(onboardingState!.tenantId, onboardingState!.slug)"
            >
              {{ activatePromptPending ? 'Activating…' : 'Activate & add first admin' }}
            </Button>
          </DialogFooter>
        </template>

        <template v-else-if="onboardingState?.kind === 'bootstrapForm'">
          <DialogHeader>
            <DialogTitle>Add {{ onboardingState!.slug }}'s first admin</DialogTitle>
            <DialogDescription>
              This address receives the onboarding link and becomes a TENANT_ADMIN for this tenant. It cannot be
              changed afterwards — reissuing a credential only re-sends to this same address.
            </DialogDescription>
          </DialogHeader>
          <form
            id="bootstrap-admin-form"
            class="flex flex-col gap-4"
            novalidate
            @submit.prevent="handleBootstrap(onboardingState!.tenantId, onboardingState!.slug)"
          >
            <FormField id="bootstrap-admin-email" label="Email" :error="bootstrapErrors.email">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="bootstrap-admin-email"
                  v-model="bootstrapForm.email"
                  type="email"
                  autocomplete="email"
                  required
                  :disabled="isBootstrapping"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <FormField id="bootstrap-admin-confirm-email" label="Confirm email" :error="bootstrapErrors.confirmEmail">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="bootstrap-admin-confirm-email"
                  v-model="bootstrapForm.confirmEmail"
                  type="email"
                  autocomplete="off"
                  required
                  :disabled="isBootstrapping"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>
            <div class="grid grid-cols-2 gap-3">
              <FormField id="bootstrap-admin-first-name" label="First name" :error="bootstrapErrors.firstName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="bootstrap-admin-first-name"
                    v-model="bootstrapForm.firstName"
                    type="text"
                    autocomplete="given-name"
                    :disabled="isBootstrapping"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
              <FormField id="bootstrap-admin-last-name" label="Last name" :error="bootstrapErrors.lastName">
                <template #default="{ hasError, describedBy }">
                  <Input
                    id="bootstrap-admin-last-name"
                    v-model="bootstrapForm.lastName"
                    type="text"
                    autocomplete="family-name"
                    :disabled="isBootstrapping"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </template>
              </FormField>
            </div>

            <p v-if="bootstrapBanner" role="alert" class="text-sm text-destructive">{{ bootstrapBanner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isBootstrapping">
                {{ isBootstrapping ? 'Creating…' : 'Create first admin' }}
              </Button>
            </DialogFooter>
          </form>
        </template>

        <template v-else-if="onboardingState?.kind === 'reissuePicker'">
          <DialogHeader>
            <DialogTitle>Reissue an onboarding credential</DialogTitle>
            <DialogDescription>
              Choose the admin to reissue for — this lists every user in "{{ onboardingState!.slug }}", not filtered
              to admins (role isn't part of this read). Reissuing re-sends a fresh link and temporary password to
              that user's existing email; it's refused if they already set their own password.
            </DialogDescription>
          </DialogHeader>
          <p v-if="reissueError" role="alert" class="text-sm text-destructive">{{ reissueError }}</p>
          <div v-if="reissueLoading" class="text-sm text-muted-foreground">Loading users…</div>
          <div v-else-if="reissueUsers && reissueUsers.length === 0" class="text-sm text-muted-foreground">
            This tenant has no users yet.
          </div>
          <ul v-else-if="reissueUsers" class="flex flex-col gap-2 max-h-72 overflow-y-auto">
            <li
              v-for="user in reissueUsers"
              :key="user.id"
              class="flex items-center justify-between gap-2 rounded-md border border-border p-2"
            >
              <span class="text-sm">{{ user.email }}</span>
              <Button
                :id="`reissue-select-${user.id}`"
                size="sm"
                variant="outline"
                :disabled="reissuePending"
                @click="confirmReissue(onboardingState!.tenantId, onboardingState!.slug, user.id, user.email)"
              >
                Reissue
              </Button>
            </li>
          </ul>
          <DialogFooter>
            <Button id="onboarding-reissue-cancel" type="button" variant="outline" @click="closeOnboarding">
              Cancel
            </Button>
          </DialogFooter>
        </template>

        <template v-else-if="onboardingState?.kind === 'success'">
          <DialogHeader>
            <DialogTitle>First admin created — {{ onboardingState!.email }}</DialogTitle>
            <DialogDescription>
              An onboarding link was emailed to that address. That is the intended path — nothing else is needed.
            </DialogDescription>
          </DialogHeader>

          <div class="flex flex-col gap-3">
            <button
              id="onboarding-disclosure-toggle"
              type="button"
              class="text-sm text-left underline underline-offset-2 text-muted-foreground self-start"
              @click="disclosureOpen = !disclosureOpen"
            >
              {{ disclosureOpen ? '▾' : '▸' }} Email didn't arrive? Use the fallback temporary password
            </button>

            <div v-if="disclosureOpen" class="flex flex-col gap-1.5 rounded-md border border-border p-3">
              <p role="alert" class="text-sm font-medium text-destructive">
                Shown ONE TIME ONLY. It cannot be retrieved again after you leave this dialog.
              </p>
              <div class="flex items-center gap-2">
                <code
                  id="onboarding-password"
                  class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all"
                >
                  {{ secretRevealed ? onboardingState!.temporaryPassword : maskedSecret(onboardingState!.temporaryPassword) }}
                </code>
                <Button id="onboarding-password-reveal" type="button" variant="outline" size="sm" @click="secretRevealed = !secretRevealed">
                  {{ secretRevealed ? 'Hide' : 'Reveal' }}
                </Button>
                <Button
                  id="onboarding-password-copy"
                  type="button"
                  variant="outline"
                  size="sm"
                  @click="copyPassword(onboardingState!.temporaryPassword)"
                >
                  {{ passwordCopied ? 'Copied' : 'Copy' }}
                </Button>
              </div>
              <p class="text-xs text-muted-foreground">
                Expires {{ new Date(onboardingState!.temporaryPasswordExpiresAt).toLocaleString() }}.
              </p>
            </div>

            <div class="flex items-start gap-2">
              <Checkbox
                id="onboarding-ack"
                :model-value="passwordAcknowledged"
                class="mt-0.5"
                @update:model-value="(v) => (passwordAcknowledged = Boolean(v))"
              />
              <Label for="onboarding-ack" class="font-normal leading-snug text-sm"> I understand this is shown once. </Label>
            </div>
          </div>

          <DialogFooter>
            <Button id="onboarding-done" type="button" :disabled="!passwordAcknowledged" @click="acknowledgeAndClose">
              Done
            </Button>
          </DialogFooter>
        </template>
      </DialogContent>
    </Dialog>
  </div>
</template>
