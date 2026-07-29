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
import { useRoute } from 'vue-router'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { submitLogin } from '@/api/authLogin'

const route = useRoute()
// client_id identifies which tenant's branding/login policy applies. Read
// from the query string a real relying-party-initiated navigation would
// carry. If absent, useOAuthTheme resolves to platform-default branding, and
// the backend's own /login falls back to the saved-authorize-request's
// client_id (LoginController.resolveClientId) — this page degrades
// gracefully either way rather than requiring the param.
const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

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
