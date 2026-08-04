<script setup lang="ts">
// Stage UI-2c-ii (consent), Deliverable 2: reached via the backend's
// consent-required redirect landing on {bffBaseUrl}/consent?client_id=...
// &scope=...&state=... (client_id/scope/state appended automatically by SAS
// itself onto the now-corrected, absolute bff.consent-page — see
// CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md). Captures window.location.search
// verbatim (same convention LoginView.vue established for the analogous
// cross-origin problem) and forwards it unchanged to this BFF's own
// GET /oauth2/consent proxy (handlers_consent_proxy.go), which augments the
// backend's real ConsentContext with one extra field, authorizeUrl — the
// backend's own real, absolute /oauth2/authorize endpoint.
//
// # THE safety-critical property this file exists to preserve
//
// The consent DECISION (approve/deny) is submitted as a genuine native HTML
// <form method="post" :action="context.authorizeUrl"> — a real top-level
// browser navigation straight to the backend's own origin. NEITHER form
// below has a @submit handler of any kind. This is deliberate, not an
// oversight: SAS already authenticates this POST correctly, same-origin, via
// TenantSessionSsoFilter — a native form navigation naturally carries the
// backend's own CLOSEAUTH_SESSION cookie because it goes directly to the
// backend's origin, no BFF hop to lose it on. Intercepting this submission
// with fetch()/XHR (even a mechanism that superficially resembled
// authLogin.ts's JSON-envelope translation) would silently reintroduce the
// exact cross-origin cookie problem this whole design avoids — login needed
// that translation because it had a genuine inline-error case (bad
// credentials) to render without navigating; consent has no such case at all
// (both approve and deny always end in a redirect), so there is nothing here
// for a fetch()-based mechanism to do that a plain form doesn't already do
// better. See CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md §1 Q4 / §5.
import { computed, onMounted, ref } from 'vue'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { useOAuthTheme } from '@/composables/useOAuthTheme'

interface ConsentScope {
  scope: string
  description: string
  requiresConsent: boolean
}

interface ConsentContext {
  clientId: string
  clientName: string
  state: string
  scopes: ConsentScope[]
  alreadyGranted: string[]
  authorizeUrl: string
}

// client_id is parsed straight off the same captured query string used for
// the context-fetch below — one source of truth, matching LoginView.vue's
// established convention for the branding composable call.
const searchParams = new URLSearchParams(window.location.search)
const clientId = searchParams.get('client_id') ?? ''

const { branding, hasLogo } = useOAuthTheme(clientId)
const companyLabel = computed(() => branding.value.companyName || 'CloseAuth')

const context = ref<ConsentContext | null>(null)
const isLoading = ref(true)
const error = ref('')

async function loadContext(): Promise<void> {
  isLoading.value = true
  error.value = ''
  try {
    const response = await fetch(`/oauth2/consent${window.location.search}`, { credentials: 'omit' })
    if (!response.ok) {
      throw new Error(`consent context fetch failed: HTTP ${response.status}`)
    }
    context.value = (await response.json()) as ConsentContext
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Failed to load this authorization request.'
  } finally {
    isLoading.value = false
  }
}

onMounted(loadContext)

// requiresConsent=true scopes render as an unchecked-by-default checkbox the
// user must actively opt into. requiresConsent=false ("auto-grantable")
// scopes are shown for transparency but rendered as a hidden input on the
// approve form (always submitted) — the backend's own
// consentAutoGrantProviders customizer grants these unconditionally
// regardless of what's submitted, so this is purely cosmetic consistency
// between what's displayed and what's sent, not a security-relevant choice.
const requiredScopes = computed(() => context.value?.scopes.filter((s) => s.requiresConsent) ?? [])
const autoGrantedScopes = computed(() => context.value?.scopes.filter((s) => !s.requiresConsent) ?? [])
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
        <h1 class="text-xl font-semibold tracking-tight">
          {{ context?.clientName ? `Authorize ${context.clientName}` : 'Authorize this application' }}
        </h1>
        <p class="text-sm text-muted-foreground">
          This app is requesting access to your {{ companyLabel }} account.
        </p>
      </div>
    </template>

    <p v-if="isLoading" class="text-sm text-muted-foreground text-center">Loading…</p>

    <p v-else-if="error" role="alert" class="text-sm text-destructive text-center">
      {{ error }}
    </p>

    <div v-else-if="context" class="flex flex-col gap-4">
      <div v-if="autoGrantedScopes.length" class="flex flex-col gap-1">
        <p v-for="s in autoGrantedScopes" :key="s.scope" class="text-sm text-muted-foreground">
          {{ s.description }}
        </p>
      </div>

      <!--
        Approve form: a genuine native POST to the backend's own origin (see
        the script header comment — no @submit handler, ever). Submits
        client_id, state, one `scope` field per checked requires-consent
        scope, plus a hidden `scope` field for every auto-granted scope
        (harmless/cosmetic — see above).
      -->
      <form
        method="post"
        :action="context.authorizeUrl"
        class="flex flex-col gap-4"
        data-testid="approve-form"
      >
        <input type="hidden" name="client_id" :value="context.clientId">
        <input type="hidden" name="state" :value="context.state">
        <input
          v-for="s in autoGrantedScopes"
          :key="s.scope"
          type="hidden"
          name="scope"
          :value="s.scope"
        >

        <div v-if="requiredScopes.length" class="flex flex-col gap-2">
          <label
            v-for="s in requiredScopes"
            :key="s.scope"
            class="flex items-start gap-2 text-sm"
          >
            <input type="checkbox" name="scope" :value="s.scope" class="mt-0.5">
            <span>{{ s.description }}</span>
          </label>
        </div>

        <Button type="submit" class="w-full">Allow</Button>
      </form>

      <!--
        Deny form: deliberately a SEPARATE <form>, carrying ONLY client_id +
        state — structurally incapable of submitting any `scope` param
        regardless of the approve form's checkbox state. This matches the
        documented deny semantics exactly (an empty approval set → SAS
        returns error=access_denied) and is never merged with the approve
        form above, so "deny" can never accidentally carry an approved scope.
      -->
      <form method="post" :action="context.authorizeUrl" data-testid="deny-form">
        <input type="hidden" name="client_id" :value="context.clientId">
        <input type="hidden" name="state" :value="context.state">
        <Button type="submit" variant="ghost" class="w-full">Deny</Button>
      </form>
    </div>
  </AuthLayout>
</template>
