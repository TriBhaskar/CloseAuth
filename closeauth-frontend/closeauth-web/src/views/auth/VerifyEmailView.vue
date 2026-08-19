<script setup lang="ts">
// Stage UI-2b, Deliverable 5 / FE-2d (spec §6.2.4): an independently
// reachable page (a real router entry — not just an inline step
// RegisterView renders itself), so a user who closes the tab after
// registering and comes back later (or clicks a bookmarked link) can still
// finish verification. Reads `email`/`client_id` from the route query if
// RegisterView routed here after a PENDING+emailVerificationSent
// registration, but both remain plain, editable inputs — reachability
// can't assume the query is always present.
//
// FE-2d adds the "link" entry (spec §6.2.4): the SAME 6-digit code, now
// also embedded in a clickable URL (`?code=…&email=…&client_id=…` —
// EmailVerificationService.verifyLinkUrl) rather than a second, separate
// secret. When `code` is present at mount, verification starts
// IMMEDIATELY — `phase`'s initial value is computed synchronously (not
// inside onMounted) specifically so the form never renders even for one
// frame first (mirrors TenantResolverView.vue's "no flash" discipline).
//
// The 429 lockout state is rendered reactively from whatever the backend's
// real response says on each submit — there is deliberately NO client-side
// cooldown timer here (the stage prompt calls this out explicitly): a local
// timer could drift out of sync with the backend's actual rate-limit window,
// while just reading the real status on every attempt never can. The 2s
// auto-continue-to-login timer (FE-2d) is a DIFFERENT kind of timer — a
// one-shot post-success navigation delay, not a rate-limit simulation — and
// doesn't conflict with that rule.
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import PinInput from '@/components/common/PinInput.vue'
import TenantBrandingProvider, { type Branding } from '@/components/common/TenantBrandingProvider.vue'
import { confirmVerification, requestVerificationResend } from '@/api/authVerifyEmail'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()
const router = useRouter()

const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

// FE-2c: RegisterView.vue now carries the full original /oauth2/authorize
// query forward as `authorize_query` — this is the receiving end. "Auto-
// continue to the pending authorization" (spec §6.2.4) can only mean
// navigating to /login with it attached: verification itself never
// establishes a session (EmailVerificationService.verify only marks the
// email verified / activates the user), so the user still has to sign in.
const authorizeQuery = computed(() => {
  const raw = route.query.authorize_query
  return typeof raw === 'string' ? raw : ''
})
const loginPath = computed(() => hostedAuthPath(route, '/login') + authorizeQuery.value)

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

const email = ref(typeof route.query.email === 'string' ? route.query.email : '')
const code = ref('')

type Phase = 'idle' | 'verifying' | 'verified' | 'expired'

const linkCode = typeof route.query.code === 'string' ? route.query.code : ''
const cameFromLink = linkCode !== '' && email.value !== ''
// Computed synchronously, at setup time — see file header. Starting
// straight at 'verifying' when a link brought us here means the form is
// never part of even the FIRST render.
const phase = ref<Phase>(cameFromLink ? 'verifying' : 'idle')

const isResending = ref(false)
const errorMessage = ref('')
const resendMessage = ref('')

let continueTimer: ReturnType<typeof setTimeout> | undefined

function scheduleAutoContinue(): void {
  continueTimer = setTimeout(() => {
    void router.push(loginPath.value)
  }, 2000)
}

onUnmounted(() => {
  if (continueTimer) clearTimeout(continueTimer)
})

async function handleConfirm(): Promise<void> {
  if (phase.value === 'verifying' && !cameFromLink) return
  errorMessage.value = ''
  resendMessage.value = ''
  phase.value = 'verifying'
  const result = await confirmVerification({
    email: email.value,
    code: code.value,
    clientId: clientId.value || undefined,
  })
  switch (result.kind) {
    case 'verified':
      // Collapses BOTH a fresh verification and an already-used code (spec
      // §6.2.4: re-clicking an old email link is success, not an error) —
      // the backend already made them indistinguishable at this point.
      phase.value = 'verified'
      scheduleAutoContinue()
      break
    case 'expired':
      phase.value = 'expired'
      break
    case 'invalidCode':
      phase.value = 'idle'
      // Generic, enumeration-safe — never says which check failed.
      errorMessage.value = 'That code is invalid. Please try again or request a new one.'
      break
    case 'rateLimited':
      phase.value = 'idle'
      errorMessage.value = 'Too many attempts. Please wait a while before trying again.'
      break
    case 'error':
    default:
      phase.value = 'idle'
      errorMessage.value = 'Something went wrong. Please try again.'
      break
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
    // Clears an `expired` state so the fresh code has somewhere to go.
    phase.value = 'idle'
    code.value = ''
  } finally {
    isResending.value = false
  }
}

onMounted(() => {
  if (cameFromLink) {
    code.value = linkCode
    void handleConfirm()
  }
})
</script>

<template>
  <TenantBrandingProvider :client-id="clientId" v-slot="{ branding, hasLogo }">
  <AuthShell>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <img
          v-if="hasLogo"
          :src="branding.logoUrl"
          :alt="companyLabel(branding)"
          class="h-10 w-auto object-contain"
        >
        <h1 class="text-xl font-semibold tracking-tight">Verify your email</h1>
        <p v-if="phase === 'idle'" class="text-sm text-muted-foreground">
          Enter the code we sent to your email address.
        </p>
      </div>
    </template>

    <!-- Link-triggered auto-consume: no form ever renders, not even for one frame. -->
    <div v-if="cameFromLink && phase === 'verifying'" class="flex flex-col items-center gap-4 text-center">
      <p class="text-sm text-muted-foreground" aria-live="polite">Verifying your email…</p>
    </div>

    <!-- Success: fresh verification AND an already-used code both land here. -->
    <div v-else-if="phase === 'verified'" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">Your email has been verified. Continuing…</p>
      <RouterLink :to="loginPath">
        <Button class="w-full">Continue to sign in</Button>
      </RouterLink>
    </div>

    <!-- Expired: its own terminal-ish state with a real recovery action. -->
    <div v-else-if="phase === 'expired'" class="flex flex-col gap-4 text-center">
      <p role="alert" class="text-sm text-foreground">This link has expired.</p>
      <Button class="w-full" :disabled="isResending" @click="handleResend">
        {{ isResending ? 'Sending…' : 'Send a new one' }}
      </Button>
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
          :disabled="phase === 'verifying'"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <Label id="verify-email-code-label" for="verify-email-code-0">Verification code</Label>
        <PinInput v-model="code" id-prefix="verify-email-code" :disabled="phase === 'verifying'" />
      </div>

      <p v-if="errorMessage" role="alert" class="text-sm text-destructive">{{ errorMessage }}</p>
      <p v-if="resendMessage" role="status" class="text-sm text-muted-foreground">{{ resendMessage }}</p>

      <Button type="submit" class="w-full" :disabled="phase === 'verifying'">
        {{ phase === 'verifying' ? 'Verifying…' : 'Verify email' }}
      </Button>
      <Button
        type="button"
        variant="ghost"
        class="w-full"
        :disabled="isResending || phase === 'verifying'"
        @click="handleResend"
      >
        {{ isResending ? 'Resending…' : 'Resend code' }}
      </Button>
    </form>
  </AuthShell>
  </TenantBrandingProvider>
</template>
