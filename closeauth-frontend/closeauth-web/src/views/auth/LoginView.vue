<script setup lang="ts">
// Stage UI-2a, Deliverable 3: the first real user-facing page in this whole
// rebuild. Uses TenantBrandingProvider (FE-1.3) for scoped branding and submits
// via the JSON login mechanism (Deliverable 1, src/api/authLogin.ts) rather
// than a native form POST — that lets a failed login render inline with no
// navigation, while a successful one performs a REAL top-level navigation
// (window.location.href, not router.push) so the cross-origin resume of
// /oauth2/authorize behaves exactly like a native form POST's redirect
// would have.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import TenantBrandingProvider, { type Branding } from '@/components/common/TenantBrandingProvider.vue'
import { submitLogin } from '@/api/authLogin'
import { saveForgotPasswordQuery } from '@/api/helpers/passwordResetContext'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()

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
// the two values can never disagree. If absent, TenantBrandingProvider
// resolves to platform-default branding, and the backend's own /login falls back to no
// resolvable tenant — this page degrades gracefully either way rather than
// requiring the param.
const clientId = computed(() => new URLSearchParams(authorizeQuery).get('client_id') ?? '')

// Stage UI-2c-i, Deliverable 3/4: magic-link's request step and the forgot-
// password flow are both reached from here via a client-side route push
// that carries the current query string forward VERBATIM — the exact same
// "capture the whole raw query string, don't decompose it" discipline as
// authorizeQuery above, for the same reason (an OIDC extra hand-enumerated
// out would silently be dropped). Neither target route strictly NEEDS this
// (their own request steps have no session/redirect dependency at all —
// see Design Decision #2), but carrying it keeps client_id-driven branding
// consistent across the hop, and — for /forgot-password specifically —
// feeds ResetPasswordView's nice-to-have post-success context restoration.
const magicLinkRequestPath = computed(() => hostedAuthPath(route, '/magic-link') + authorizeQuery)
const forgotPasswordPath = computed(() => hostedAuthPath(route, '/forgot-password') + authorizeQuery)
const registerPath = computed(() => hostedAuthPath(route, '/register') + authorizeQuery)

// Best-effort: stash the query string so ResetPasswordView.vue's post-
// success "log in" link can restore it IF the reset link is opened in this
// same browser session (see passwordResetContext.ts's header comment for
// why this is a nice-to-have, not a guarantee).
function handleForgotPasswordClick(): void {
  saveForgotPasswordQuery(authorizeQuery)
}

const email = ref('')
const password = ref('')
const rememberMe = ref(false)
const isSubmitting = ref(false)
const errorMessage = ref('')

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

// FE-2b (spec §6.2.2): "Create an account... shown only in SELF_SERVICE
// registration mode." §6.2.3's own mode table marks BOTH self-service
// variants "open" (SELF_SERVICE and SELF_SERVICE_WITH_VERIFICATION), so this
// reads as "open registration, in either verification posture" rather than
// excluding the verified one. The real backend enum
// (tenant/enums/RegistrationMode.java) uses OPEN/EMAIL_VERIFIED for those
// two — 'SELF_SERVICE'/'SELF_SERVICE_WITH_VERIFICATION' are spec-document
// names only, never an actual value on the wire (confirmed against
// api/tenantAdminRegistrationConfig.ts, the console's own authenticated
// counterpart, which already uses the real names). `null`/`ADMIN_APPROVED`/
// `INVITE_ONLY` all hide the link.
const OPEN_REGISTRATION_MODES = new Set(['OPEN', 'EMAIL_VERIFIED'])

function showCreateAccount(branding: Branding): boolean {
  return branding.registrationMode !== null && OPEN_REGISTRATION_MODES.has(branding.registrationMode)
}

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
        <h1 class="text-xl font-semibold tracking-tight">Sign in to {{ companyLabel(branding) }}</h1>
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
        <div class="flex items-center justify-between">
          <Label for="login-password">Password</Label>
          <RouterLink
            :to="forgotPasswordPath"
            class="text-sm text-muted-foreground hover:underline"
            @click="handleForgotPasswordClick"
          >
            Forgot password?
          </RouterLink>
        </div>
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

      <RouterLink :to="magicLinkRequestPath" class="text-sm text-center text-muted-foreground hover:underline">
        Email me a link instead
      </RouterLink>

      <RouterLink
        v-if="showCreateAccount(branding)"
        :to="registerPath"
        class="text-sm text-center text-muted-foreground hover:underline"
      >
        Create an account
      </RouterLink>
    </form>
  </AuthShell>
  </TenantBrandingProvider>
</template>
