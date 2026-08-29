<script setup lang="ts">
// Stage UI-4 / FE-3a: the platform-admin console's login form. Genuinely NOT
// LoginView.vue's pattern — that page submits via a JSON-translated redirect
// envelope and finishes with a real top-level navigation into the backend's
// OAuth2 dance; this page calls a plain JSON login endpoint
// (api/platformAdminSession.ts's login()) and, on success, just pushes into
// the guarded console route client-side. No client_id, no branding lookup,
// no PKCE — reuses EntryShell only for its shell (header/card/footer), none
// of AuthShell's OAuth-flow/tenant-branding assumptions (spec §6.3.1:
// "EntryShell, unbranded, title Platform sign in").
//
// The T-60s "Stay signed in" countdown and the on-expiry interstitial
// re-auth dialog (also FE-3a, spec §6.3.1) live in layouts/PlatformAdminLayout.vue
// instead — they apply to the whole signed-in console, not this one screen.
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EntryShell from '@/shells/EntryShell.vue'
import FormField from '@/components/common/FormField.vue'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { login } from '@/api/platformAdminSession'
import { isSameOriginPath } from '@/lib/safePath'

const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return
  errorMessage.value = ''
  isSubmitting.value = true
  try {
    const result = await login(email.value, password.value)
    switch (result.kind) {
      case 'ok': {
        // FE-6.6: guards.ts's own SLUG_PATTERN discipline, applied here too
        // — a returnTo it sets itself is always root-relative (to.fullPath),
        // but this reads straight from the URL query string, so it's
        // validated the same way rather than trusted on shape alone.
        const requested = typeof route.query.returnTo === 'string' ? route.query.returnTo : ''
        const returnTo = isSameOriginPath(requested) ? requested : '/platform/console'
        void router.push(returnTo)
        break
      }
      case 'invalidCredentials':
        // Uniform, enumeration-safe — never "no such admin" / "wrong password".
        errorMessage.value = 'Incorrect email or password. Please try again.'
        break
      case 'notPlatformAdmin':
        // FE-6.6: uniform with invalidCredentials, not a distinct message —
        // a different string here is a pre-auth account-existence oracle
        // ("this account exists but isn't platform-admin"), which is exactly
        // what spec's enumeration-safety principle (§6.2.2) forbids
        // elsewhere in the app. See PlatformAdminLayout.vue's identical fix
        // for the reauth-overlay path.
        errorMessage.value = 'Incorrect email or password. Please try again.'
        break
      case 'unreachable':
        errorMessage.value = 'Could not reach the server. Please try again.'
        break
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <EntryShell>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <h1 class="text-xl font-semibold tracking-tight">Platform sign in</h1>
        <p class="text-sm text-muted-foreground">
          CloseAuth staff sign-in. Sessions are 5 minutes and are not silently renewed.
        </p>
      </div>
    </template>

    <form class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
      <FormField id="platform-login-email" label="Email">
        <template #default="{ hasError, describedBy }">
          <Input
            id="platform-login-email"
            v-model="email"
            type="email"
            autocomplete="email"
            required
            :disabled="isSubmitting"
            :aria-invalid="hasError"
            :aria-describedby="describedBy"
          />
        </template>
      </FormField>

      <FormField id="platform-login-password" label="Password">
        <template #default="{ hasError, describedBy }">
          <Input
            id="platform-login-password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
            :disabled="isSubmitting"
            :aria-invalid="hasError"
            :aria-describedby="describedBy"
          />
        </template>
      </FormField>

      <p v-if="errorMessage" role="alert" class="text-sm text-destructive">
        {{ errorMessage }}
      </p>

      <Button type="submit" class="w-full" :disabled="isSubmitting">
        {{ isSubmitting ? 'Signing in…' : 'Sign in' }}
      </Button>
    </form>

    <template #footer>
      <RouterLink to="/" class="text-sm text-center text-muted-foreground hover:underline block">
        ← Back to workspace sign-in
      </RouterLink>
    </template>
  </EntryShell>
</template>
