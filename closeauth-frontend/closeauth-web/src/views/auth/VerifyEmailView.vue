<script setup lang="ts">
// Stage UI-2b, Deliverable 5: an independently reachable page (a real router
// entry — /verify-email — not just an inline step RegisterView renders
// itself), so a user who closes the tab after registering and comes back
// later (or clicks a bookmarked link) can still finish verification. Reads
// `email`/`client_id` from the route query if RegisterView routed here after
// a PENDING+emailVerificationSent registration, but both remain plain,
// editable inputs — reachability can't assume the query is always present.
//
// The 429 lockout state is rendered reactively from whatever the backend's
// real response says on each submit — there is deliberately NO client-side
// cooldown timer here (the stage prompt calls this out explicitly): a local
// timer could drift out of sync with the backend's actual rate-limit window,
// while just reading the real status on every attempt never can.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { confirmVerification, requestVerificationResend } from '@/api/authVerifyEmail'

const route = useRoute()

const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

const { branding, hasLogo } = useOAuthTheme(clientId.value)
const companyLabel = computed(() => branding.value.companyName || 'CloseAuth')

const email = ref(typeof route.query.email === 'string' ? route.query.email : '')
const code = ref('')

const isSubmitting = ref(false)
const isResending = ref(false)
const errorMessage = ref('')
const resendMessage = ref('')
const isVerified = ref(false)

async function handleConfirm(): Promise<void> {
  if (isSubmitting.value) return
  errorMessage.value = ''
  resendMessage.value = ''
  isSubmitting.value = true
  try {
    const result = await confirmVerification({
      email: email.value,
      code: code.value,
      clientId: clientId.value || undefined,
    })
    switch (result.kind) {
      case 'verified':
        isVerified.value = true
        break
      case 'invalidCode':
        // Generic, enumeration-safe — never says which check failed
        // (bad/expired/used code are all indistinguishable, per the backend).
        errorMessage.value = 'That code is invalid or has expired. Please try again or request a new one.'
        break
      case 'rateLimited':
        errorMessage.value = 'Too many attempts. Please wait a while before trying again.'
        break
      case 'error':
      default:
        errorMessage.value = 'Something went wrong. Please try again.'
        break
    }
  } finally {
    isSubmitting.value = false
  }
}

async function handleResend(): Promise<void> {
  if (isResending.value) return
  errorMessage.value = ''
  resendMessage.value = ''
  isResending.value = true
  try {
    // Always uniform on the backend (200 regardless of whether the email
    // maps to a real, still-PENDING user) — the message here matches that
    // enumeration-safety, rather than confirming anything about the account.
    await requestVerificationResend({ email: email.value, clientId: clientId.value || undefined })
    resendMessage.value = 'If that email has a pending verification, a new code has been sent.'
  } finally {
    isResending.value = false
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
        <h1 class="text-xl font-semibold tracking-tight">Verify your email</h1>
        <p class="text-sm text-muted-foreground">Enter the code we sent to your email address.</p>
      </div>
    </template>

    <!-- Success: the same "please log in" outcome as a successful registration -->
    <div v-if="isVerified" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">Your email has been verified. You can now sign in.</p>
      <RouterLink to="/login">
        <Button class="w-full">Continue to sign in</Button>
      </RouterLink>
    </div>

    <form v-else class="flex flex-col gap-4" novalidate @submit.prevent="handleConfirm">
      <div class="flex flex-col gap-1.5">
        <Label for="verify-email-address">Email</Label>
        <Input
          id="verify-email-address"
          v-model="email"
          type="email"
          autocomplete="email"
          required
          :disabled="isSubmitting"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <Label for="verify-email-code">Verification code</Label>
        <Input
          id="verify-email-code"
          v-model="code"
          type="text"
          inputmode="numeric"
          autocomplete="one-time-code"
          required
          :disabled="isSubmitting"
        />
      </div>

      <p v-if="errorMessage" role="alert" class="text-sm text-destructive">{{ errorMessage }}</p>
      <p v-if="resendMessage" role="status" class="text-sm text-muted-foreground">{{ resendMessage }}</p>

      <Button type="submit" class="w-full" :disabled="isSubmitting">
        {{ isSubmitting ? 'Verifying…' : 'Verify email' }}
      </Button>
      <Button
        type="button"
        variant="ghost"
        class="w-full"
        :disabled="isResending || isSubmitting"
        @click="handleResend"
      >
        {{ isResending ? 'Resending…' : 'Resend code' }}
      </Button>
    </form>
  </AuthLayout>
</template>
