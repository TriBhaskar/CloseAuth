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
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import NewPasswordFields from '@/components/auth/NewPasswordFields.vue'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { confirmPasswordReset } from '@/api/authPasswordReset'
import { readForgotPasswordQuery } from '@/api/helpers/passwordResetContext'

const route = useRoute()

const token = computed(() => {
  const raw = route.query.token
  return typeof raw === 'string' ? raw : ''
})
const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

const { branding, hasLogo } = useOAuthTheme(clientId.value)
const companyLabel = computed(() => branding.value.companyName || 'CloseAuth')

const bannerMessage = ref('')
const isSubmitting = ref(false)
const isReset = ref(false)

// Best-effort restore: a saved query string (client_id, redirect_uri,
// state, ...) from the same browser session, or an empty string if none —
// see this file's header comment for why a bare fallback is fine.
const loginHref = computed(() => {
  const savedQuery = readForgotPasswordQuery()
  return savedQuery ? `/login${savedQuery}` : '/login'
})

async function handleSubmit(password: string): Promise<void> {
  if (isSubmitting.value) return
  bannerMessage.value = ''

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
        // invalid, expired, or already used.
        bannerMessage.value = 'This reset link is invalid or has expired. Please request a new one.'
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
  <AuthLayout>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <img
          v-if="hasLogo"
          :src="branding.logoUrl"
          :alt="companyLabel"
          class="h-10 w-auto object-contain"
        >
        <h1 class="text-xl font-semibold tracking-tight">Set a new password for {{ companyLabel }}</h1>
        <p class="text-sm text-muted-foreground">Choose a new password for your account.</p>
      </div>
    </template>

    <!-- Success: the same "please log in" outcome as registration/verification -->
    <div v-if="isReset" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">Your password has been reset. You can now sign in.</p>
      <RouterLink :to="loginHref">
        <Button class="w-full">Continue to sign in</Button>
      </RouterLink>
    </div>

    <NewPasswordFields
      v-else
      id-prefix="reset-password"
      submit-label="Reset password"
      submitting-label="Resetting…"
      :is-submitting="isSubmitting"
      :banner-message="bannerMessage"
      @submit="handleSubmit"
    />
  </AuthLayout>
</template>
