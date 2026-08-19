// FE-3b: extracted from PlatformTenantsView.vue (originally Stage UI-4b)
// once a genuine second consumer arrived in the same session — the new
// tenant detail page needs the identical bootstrap-first-admin / reissue /
// success hand-off flow the list view already had.
//
// Two behavioural changes from the pre-FE-3b version, both decided this
// session:
//  - the old 'activatePrompt' three-way choice (Later / Activate only /
//    Activate & add first admin) is gone. The provision->bootstrap chain
//    now auto-activates (see PlatformTenantsView.vue's handleProvision,
//    which calls activateAndOpenBootstrap directly) — this composable only
//    surfaces an 'activateFailed' recovery state for the rare case that
//    single activate call itself fails.
//  - the one-time temporary-password hand-off is no longer a hand-rolled
//    mask/reveal/copy block nested inside the SAME onboarding <Dialog> as
//    the bootstrap form. It's now the shared, already-built
//    SecretRevealPanel — which is itself a full <Dialog>, so nesting it
//    inside another already-open one would recreate the exact nested-dialog
//    focus-trap risk FE-3a avoided for the session-expiry overlay. Instead:
//    showSuccess() closes onboardingState (the bootstrap/reissue dialog) and
//    opens successPanel in the same call — the two are never open at once.
import { computed, reactive, ref } from 'vue'
import {
  activateTenant,
  type TenantView,
} from '@/api/platformAdminTenants'
import {
  bootstrapTenantAdmin,
  listTenantUsers,
  reissueOnboardingCredential,
  type TenantUserView,
} from '@/api/platformAdminTenantUsers'
import { describeAdminError } from '@/api/problem'
import { tenantSignInUrl } from '@/lib/tenantSignInUrl'
import type { SecretField } from '@/components/common/SecretRevealPanel.vue'

type OnboardingState =
  | { kind: 'bootstrapForm'; tenantId: string; slug: string }
  | { kind: 'reissuePicker'; tenantId: string; slug: string }
  | { kind: 'activateFailed'; tenantId: string; slug: string; name: string; message: string }

export interface OnboardingSuccessPanel {
  tenantId: string
  slug: string
  email: string
  temporaryPassword: string
  temporaryPasswordExpiresAt: string
}

export function useTenantOnboarding(onSuccess: () => void | Promise<void>) {
  const onboardingState = ref<OnboardingState | null>(null)
  const successPanel = ref<OnboardingSuccessPanel | null>(null)

  function closeOnboarding(): void {
    onboardingState.value = null
  }

  function showSuccess(
    tenantId: string,
    slug: string,
    email: string,
    temporaryPassword: string,
    temporaryPasswordExpiresAt: string,
  ): void {
    onboardingState.value = null
    successPanel.value = { tenantId, slug, email, temporaryPassword, temporaryPasswordExpiresAt }
    void onSuccess()
  }

  function closeSuccessPanel(): void {
    successPanel.value = null
  }

  const successPanelFields = computed<SecretField[]>(() => {
    if (!successPanel.value) return []
    const { slug, temporaryPassword, temporaryPasswordExpiresAt } = successPanel.value
    return [
      { id: 'tenant-id', label: 'Tenant ID', value: slug },
      { id: 'signin-url', label: 'Sign-in URL', value: tenantSignInUrl(slug) },
      {
        id: 'temp-password',
        label: 'Temporary password',
        value: temporaryPassword,
        maskable: true,
        hint: `Expires ${new Date(temporaryPasswordExpiresAt).toLocaleString()}.`,
      },
    ]
  })

  // ---- auto-activate (provision chain) + its retry -----------------------

  const activatePending = ref(false)

  async function activateAndOpenBootstrap(tenantId: string, slug: string, name: string): Promise<void> {
    activatePending.value = true
    try {
      const result = await activateTenant(tenantId)
      switch (result.kind) {
        case 'ok':
          await onSuccess()
          openBootstrapForm(tenantId, slug)
          break
        case 'reauth':
          break
        default: {
          const message =
            result.code === 'session_expired'
              ? `Your platform session expired. The tenant "${name}" was created and is listed — sign in again, then use its row's own Activate action.`
              : "Tenant created, but couldn't be activated yet."
          onboardingState.value = { kind: 'activateFailed', tenantId, slug, name, message }
          break
        }
      }
    } finally {
      activatePending.value = false
    }
  }

  function retryActivate(): void {
    if (activatePending.value || onboardingState.value?.kind !== 'activateFailed') return
    const { tenantId, slug, name } = onboardingState.value
    void activateAndOpenBootstrap(tenantId, slug, name)
  }

  // ---- bootstrap-admin form ------------------------------------------

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

  function openBootstrapForm(tenantId: string, slug: string): void {
    resetBootstrapForm()
    onboardingState.value = { kind: 'bootstrapForm', tenantId, slug }
  }

  async function handleBootstrap(): Promise<void> {
    if (onboardingState.value?.kind !== 'bootstrapForm' || isBootstrapping.value) return
    const { tenantId, slug } = onboardingState.value
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
          showSuccess(tenantId, slug, result.value.user.email, result.value.temporaryPassword, result.value.temporaryPasswordExpiresAt)
          break
        case 'validationErrors':
          Object.assign(bootstrapErrors, result.errors)
          break
        case 'conflict':
          bootstrapBanner.value =
            result.code === 'tenant_onboarding.admin_already_exists'
              ? 'This tenant already has an active admin. Use "Reissue credential" if their onboarding link was never used.'
              : result.code === 'user.email_conflict'
                ? 'A user with this email already exists in this tenant.'
                : result.message
          break
        case 'reauth':
          break
        default:
          // Spec's own recovery framing for the known 403 mid-wizard trap
          // (a tenant not yet ACTIVE by the time this write lands) — never a
          // raw error page, and the form itself (still open, still
          // resubmittable) IS the retry affordance.
          bootstrapBanner.value =
            result.code === 'session_expired'
              ? `Your platform session expired. The tenant "${slug}" was created and is listed — nothing was lost. Sign in again and check the row: "No admin" means retry; an admin already showing means check whether the onboarding email arrived before reaching for the temporary password.`
              : result.code === 'tenant.not_active'
                ? "Tenant created, but the admin couldn't be added yet. Retry."
                : describeAdminError(result)
          break
      }
    } finally {
      isBootstrapping.value = false
    }
  }

  // ---- reissue picker -----------------------------------------------

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
        reissueError.value =
          result.code === 'session_expired'
            ? `Your platform session expired. The tenant "${slug}" was created and is listed — sign in again and use "Reissue credential" to retry.`
            : describeAdminError(result)
        break
    }
  }

  async function confirmReissue(userId: string, userEmail: string): Promise<void> {
    if (onboardingState.value?.kind !== 'reissuePicker' || reissuePending.value) return
    const { tenantId, slug } = onboardingState.value
    reissueError.value = ''
    reissuePending.value = true
    try {
      const result = await reissueOnboardingCredential(tenantId, userId)
      switch (result.kind) {
        case 'ok':
          showSuccess(tenantId, slug, userEmail, result.value.temporaryPassword, result.value.temporaryPasswordExpiresAt)
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
          reissueError.value =
            result.code === 'session_expired'
              ? `Your platform session expired. The tenant "${slug}" was created and is listed — sign in again and use "Reissue credential" on this admin to retry.`
              : describeAdminError(result)
          break
      }
    } finally {
      reissuePending.value = false
    }
  }

  // Onboarding-action classification, shared by both consumers: what a
  // tenant's row/page should offer given status AND adminCount — not a pure
  // lifecycle-state-machine transition, so it stays separate from
  // availableTenantActions.
  function onboardingAction(tenant: TenantView): 'bootstrap' | 'reissue' | null {
    if (tenant.status !== 'ACTIVE' || tenant.adminCount === null) return null
    return tenant.adminCount === 0 ? 'bootstrap' : 'reissue'
  }

  return {
    onboardingState,
    successPanel,
    successPanelFields,
    closeOnboarding,
    closeSuccessPanel,
    activatePending,
    activateAndOpenBootstrap,
    retryActivate,
    bootstrapForm,
    bootstrapErrors,
    bootstrapBanner,
    isBootstrapping,
    openBootstrapForm,
    handleBootstrap,
    reissueUsers,
    reissueLoading,
    reissueError,
    reissuePending,
    openReissuePicker,
    confirmReissue,
    onboardingAction,
  }
}
