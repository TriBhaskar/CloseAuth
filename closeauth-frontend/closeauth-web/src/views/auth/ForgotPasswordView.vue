<script setup lang="ts">
// Stage UI-2c-i, Deliverable 4: password reset's REQUEST step. Reached via
// LoginView.vue's "Forgot password?" link (a client-side route push
// carrying the current query string forward verbatim, same as
// MagicLinkRequestView.vue). Plain email form; submit always resolves to
// the same enumeration-safe "check your email" confirmation shape — POST
// /password-reset/request is uniformly 200 regardless of whether the
// account exists (PasswordResetController source).
//
// Also writes the current query string to sessionStorage on submit (not
// just on LoginView's link click) — belt-and-suspenders for the nice-to-have
// post-reset context restoration (see passwordResetContext.ts): a user
// could land on this view directly (bookmark, typed URL) without ever
// clicking through LoginView, and this way ResetPasswordView.vue's restore
// attempt still has something to read back if that happens in the same
// session.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import TenantBrandingProvider, {
  type Branding,
} from '@/components/common/TenantBrandingProvider.vue'
import { requestPasswordReset } from '@/api/authPasswordReset'
import { saveForgotPasswordQuery } from '@/api/helpers/passwordResetContext'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()

const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

const loginPath = computed(() => hostedAuthPath(route, '/login'))

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

const email = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')
const isSent = ref(false)

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return
  errorMessage.value = ''
  isSubmitting.value = true
  try {
    saveForgotPasswordQuery(window.location.search)
    const ok = await requestPasswordReset({
      email: email.value,
      clientId: clientId.value || undefined,
    })
    if (ok) {
      isSent.value = true
    } else {
      errorMessage.value = 'Something went wrong. Please try again.'
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
          <h1 class="text-xl font-semibold tracking-tight">Reset your password</h1>
          <p class="text-sm text-muted-foreground">
            Enter your email and we'll send you a reset link.
          </p>
        </div>
      </template>

      <!-- Success: enumeration-safe confirmation, identical whether or not the account exists -->
      <div v-if="isSent" class="flex flex-col gap-4 text-center">
        <p class="text-sm text-foreground">
          If that account exists, we've sent reset instructions.
        </p>
        <RouterLink :to="loginPath">
          <Button variant="ghost" class="w-full">Back to sign in</Button>
        </RouterLink>
      </div>

      <form v-else class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
        <div class="flex flex-col gap-1.5">
          <Label for="forgot-password-email">Email</Label>
          <Input
            id="forgot-password-email"
            v-model="email"
            type="email"
            autocomplete="email"
            required
            :disabled="isSubmitting"
          />
        </div>

        <p v-if="errorMessage" role="alert" class="text-sm text-destructive">{{ errorMessage }}</p>

        <Button type="submit" class="w-full" :disabled="isSubmitting">
          {{ isSubmitting ? 'Sending…' : 'Send reset link' }}
        </Button>

        <RouterLink
          :to="loginPath"
          class="text-sm text-center text-muted-foreground hover:underline"
        >
          Back to sign in
        </RouterLink>
      </form>
    </AuthShell>
  </TenantBrandingProvider>
</template>
