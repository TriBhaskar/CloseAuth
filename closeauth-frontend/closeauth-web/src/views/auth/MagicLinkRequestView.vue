<script setup lang="ts">
// Stage UI-2c-i, Deliverable 3: magic-link's REQUEST step only. Reached via
// LoginView.vue's "Email me a link instead" link (a client-side route push
// carrying the current query string forward verbatim). The CONSUME step
// (following the emailed link) needs no corresponding view here at all —
// Design Decision #1: the backend emails a link straight to its own origin
// (GET /magic-link/consume), never through this SPA, so there is nothing
// for a Vue route to render on that side.
//
// Plain email form; submit always resolves to the same enumeration-safe
// "check your email" confirmation shape already established by
// VerifyEmailView.vue's resend / RegisterView.vue's PENDING+verify branch —
// POST /magic-link/request is uniformly 200 regardless of whether the
// account exists (MagicLinkController source), so this view never has a
// per-outcome branch to make on the happy path; the only distinct branch is
// a genuine network/transport failure (authMagicLink.ts's boolean `false`).
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import TenantBrandingProvider, { type Branding } from '@/components/common/TenantBrandingProvider.vue'
import { requestMagicLink } from '@/api/authMagicLink'
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
    const ok = await requestMagicLink({ email: email.value, clientId: clientId.value || undefined })
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
        <img
          v-if="hasLogo"
          :src="branding.logoUrl"
          :alt="companyLabel(branding)"
          class="h-10 w-auto object-contain"
        >
        <h1 class="text-xl font-semibold tracking-tight">Sign in with a link</h1>
        <p class="text-sm text-muted-foreground">We'll email you a link to sign in — no password needed.</p>
      </div>
    </template>

    <!-- Success: enumeration-safe confirmation, identical whether or not the account exists -->
    <div v-if="isSent" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">
        If that account exists, a sign-in link is on its way.
      </p>
      <RouterLink :to="loginPath">
        <Button variant="ghost" class="w-full">Back to sign in</Button>
      </RouterLink>
    </div>

    <form v-else class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
      <div class="flex flex-col gap-1.5">
        <Label for="magic-link-email">Email</Label>
        <Input
          id="magic-link-email"
          v-model="email"
          type="email"
          autocomplete="email"
          required
          :disabled="isSubmitting"
        />
      </div>

      <p v-if="errorMessage" role="alert" class="text-sm text-destructive">{{ errorMessage }}</p>

      <Button type="submit" class="w-full" :disabled="isSubmitting">
        {{ isSubmitting ? 'Sending…' : 'Email me a link' }}
      </Button>

      <RouterLink :to="loginPath" class="text-sm text-center text-muted-foreground hover:underline">
        Back to sign in
      </RouterLink>
    </form>
  </AuthShell>
  </TenantBrandingProvider>
</template>
