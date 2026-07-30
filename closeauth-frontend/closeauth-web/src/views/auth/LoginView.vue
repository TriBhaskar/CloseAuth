<script setup lang="ts">
// Stage UI-2a, Deliverable 3: the first real user-facing page in this whole
// rebuild. Uses the shared branding composable (Deliverable 2) and submits
// via the JSON login mechanism (Deliverable 1, src/api/authLogin.ts) rather
// than a native form POST — that lets a failed login render inline with no
// navigation, while a successful one performs a REAL top-level navigation
// (window.location.href, not router.push) so the cross-origin resume of
// /oauth2/authorize behaves exactly like a native form POST's redirect
// would have.
import { computed, ref } from 'vue'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { submitLogin } from '@/api/authLogin'

// Cross-origin login continuity (CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3b):
// capture the ENTIRE raw query string once, at mount, straight off
// window.location.search — e.g. "?client_id=...&redirect_uri=...&state=...
// &nonce=..." — rather than decomposing it into named fields via
// route.query. This is what the backend's entry point now appends onto the
// redirect to this page (LoginUrlAuthenticationEntryPoint override), and
// carrying it verbatim (not hand-enumerated) is exactly the choice the
// backend itself made, for the same reason: a named-field allowlist would
// silently drop OIDC extras (nonce, prompt, ...) a relying party may send.
const authorizeQuery = window.location.search

// client_id identifies which tenant's branding/login policy applies. Derived
// from the SAME captured string above (not a separate route.query read) so
// the two values can never disagree. If absent, useOAuthTheme resolves to
// platform-default branding, and the backend's own /login falls back to no
// resolvable tenant — this page degrades gracefully either way rather than
// requiring the param.
const clientId = computed(() => new URLSearchParams(authorizeQuery).get('client_id') ?? '')

const { branding, hasLogo } = useOAuthTheme(clientId.value)

const email = ref('')
const password = ref('')
const rememberMe = ref(false)
const isSubmitting = ref(false)
const errorMessage = ref('')

const companyLabel = computed(() => branding.value.companyName || 'CloseAuth')

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return
  errorMessage.value = ''
  isSubmitting.value = true
  try {
    const result = await submitLogin({
      email: email.value,
      password: password.value,
      rememberMe: rememberMe.value,
      clientId: clientId.value || undefined,
      authorizeQuery: authorizeQuery || undefined,
    })
    if (result.ok) {
      // Real top-level navigation — see file header comment for why this
      // must NOT be router.push. This may leave the SPA entirely (the
      // backend's own origin, and beyond that, potentially the relying
      // party's own third-party redirect_uri).
      window.location.href = result.redirectTo
      return
    }
    // Uniform, enumeration-safe failure — never reveals which factor failed.
    errorMessage.value = 'Incorrect email or password. Please try again.'
  } finally {
    // Only reset on the failure path — on success we're navigating away, so
    // there's no more "submitting" state to show.
    if (errorMessage.value) {
      isSubmitting.value = false
    }
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
        <h1 class="text-xl font-semibold tracking-tight">Sign in to {{ companyLabel }}</h1>
        <p class="text-sm text-muted-foreground">Enter your credentials to continue.</p>
      </div>
    </template>

    <form class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
      <div class="flex flex-col gap-1.5">
        <Label for="login-email">Email</Label>
        <Input
          id="login-email"
          v-model="email"
          type="email"
          autocomplete="email"
          required
          :disabled="isSubmitting"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <Label for="login-password">Password</Label>
        <Input
          id="login-password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="isSubmitting"
        />
      </div>

      <div class="flex items-center gap-2">
        <Checkbox id="login-remember-me" v-model="rememberMe" :disabled="isSubmitting" />
        <Label for="login-remember-me" class="text-muted-foreground font-normal">Remember me</Label>
      </div>

      <p v-if="errorMessage" role="alert" class="text-sm text-destructive">
        {{ errorMessage }}
      </p>

      <Button type="submit" class="w-full" :disabled="isSubmitting">
        {{ isSubmitting ? 'Signing in…' : 'Sign in' }}
      </Button>
    </form>
  </AuthLayout>
</template>
