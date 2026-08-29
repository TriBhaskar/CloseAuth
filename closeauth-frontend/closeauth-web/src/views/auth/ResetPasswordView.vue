<script setup lang="ts">
// Stage UI-2c-i, Deliverable 4: the emailed password-reset link's landing
// page. Reads `token` + `client_id` from the URL query — the exact param
// names PasswordResetService.resetUrl builds (confirmed read-only against
// backend source; see the stage report) — a real Vue-rendered form is
// required here (unlike magic-link's pure-click consume step), since the
// user has to actually type a new password.
//
// New-password + confirm-password, with a client-side match check (a
// nicety, not a substitute for the backend's own validation). On success:
// the same "please log in" outcome pattern as RegisterView.vue/
// VerifyEmailView.vue's success states. On a 400 (invalid/expired/used
// token): a generic inline error — never enumerates which specific reason,
// matching the backend's own posture.
//
// Nice-to-have: attempts to restore the original OAuth context on the
// post-success "log in" link via sessionStorage (passwordResetContext.ts) —
// written when "Forgot password?" was clicked on LoginView.vue (or when
// ForgotPasswordView.vue was submitted directly). If nothing was saved
// (this link opened in a different tab/browser than the one that requested
// it — the common case for email links), the fallback is a bare `/login`
// with no client_id, which is a normal, expected outcome, not an error.
//
// Phase 4a: the field pair + match check now live in the shared
// NewPasswordFields.vue (per decision 8's intent — one implementation of the
// "type a new password twice" form, reused by the new rotation view). The
// submit target, success semantics, and error copy below are UNCHANGED from
// before that extraction — idPrefix="reset-password" keeps this view's
// element ids identical, so ResetPasswordView.spec.ts required no edits.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'
import NewPasswordFields from '@/components/common/NewPasswordFields.vue'
import TenantBrandingProvider, {
  type Branding,
} from '@/components/common/TenantBrandingProvider.vue'
import { confirmPasswordReset } from '@/api/authPasswordReset'
import { readForgotPasswordQuery } from '@/api/helpers/passwordResetContext'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()

const token = computed(() => {
  const raw = route.query.token
  return typeof raw === 'string' ? raw : ''
})
const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

const bannerMessage = ref('')
const isSubmitting = ref(false)
const isReset = ref(false)
// FE-2e (spec §6.2.6): only the `invalid` outcome gets a "Request a new
// one" recovery action — a genuine network/transport failure isn't about
// the link being bad, so a plain retry (resubmit the same form) is the
// more honest affordance there, not a link to request a fresh one.
const showRequestNew = ref(false)
const forgotPasswordPath = computed(() => hostedAuthPath(route, '/forgot-password'))

// Best-effort restore: a saved query string (client_id, redirect_uri,
// state, ...) from the same browser session, or an empty string if none —
// see this file's header comment for why a bare fallback is fine.
const loginHref = computed(() => {
  const savedQuery = readForgotPasswordQuery()
  const base = hostedAuthPath(route, '/login')
  return savedQuery ? `${base}${savedQuery}` : base
})

async function handleSubmit(password: string): Promise<void> {
  if (isSubmitting.value) return
  bannerMessage.value = ''
  showRequestNew.value = false

  isSubmitting.value = true
  try {
    const result = await confirmPasswordReset({
      token: token.value,
      password,
      clientId: clientId.value || undefined,
    })

    switch (result.kind) {
      case 'reset':
        isReset.value = true
        break
      case 'invalid':
        // Generic, enumeration-safe — never says whether the token was
        // invalid, expired, or already used (the backend collapses all
        // three; see PasswordResetService). "Expired" is the blanket copy
        // for this one outcome — by far the most common real reason a
        // token fails, and this flow's high-entropy token makes
        // distinguishing further pointless either way (unlike email
        // verification's low-entropy code, FE-2d).
        bannerMessage.value = 'This reset link has expired.'
        showRequestNew.value = true
        break
      case 'error':
      default:
        bannerMessage.value = 'Something went wrong. Please try again.'
        break
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <TenantBrandingProvider :client-id="clientId" v-slot="{ branding, hasLogo }">
    <AuthShell>
      <template #above>
        <div class="flex flex-col items-center gap-2 text-center">
          <!-- FE-6.5: reserved box — see LoginView.vue's identical fix. -->
          <div class="h-10">
            <img
              v-if="hasLogo"
              :src="branding.logoUrl"
              :alt="companyLabel(branding)"
              class="h-10 w-auto object-contain"
            />
          </div>
          <h1 class="text-xl font-semibold tracking-tight">
            Set a new password for {{ companyLabel(branding) }}
          </h1>
          <p class="text-sm text-muted-foreground">Choose a new password for your account.</p>
        </div>
      </template>

      <!-- Success: the same "please log in" outcome as registration/verification -->
      <div v-if="isReset" class="flex flex-col gap-4 text-center">
        <p class="text-sm font-semibold text-foreground">Password updated</p>
        <p class="text-sm text-foreground">You've been signed out on all devices.</p>
        <RouterLink :to="loginHref">
          <Button class="w-full">Continue to sign in</Button>
        </RouterLink>
      </div>

      <template v-else>
        <NewPasswordFields
          id-prefix="reset-password"
          submit-label="Reset password"
          submitting-label="Resetting…"
          :is-submitting="isSubmitting"
          :banner-message="bannerMessage"
          @submit="handleSubmit"
        />
        <RouterLink
          v-if="showRequestNew"
          :to="forgotPasswordPath"
          class="text-sm text-center text-muted-foreground hover:underline"
        >
          Request a new one
        </RouterLink>
      </template>
    </AuthShell>
  </TenantBrandingProvider>
</template>
