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
// # BE-B: the two-hop redirect to /t/{slug}/consent
//
// Spec §2.2 wants every hosted-auth page under /t/{tenantId}/..., but Spring
// Authorization Server's consentPage() only accepts a literal, config-time
// URL — verified against the actual vendored SAS 1.5.1 source, no
// per-request templating exists, so SAS can never be made to redirect
// straight to a tenant-namespaced URL. This component is therefore
// registered at BOTH /consent (where SAS actually lands the browser) and
// /t/:slug/consent (the spec-shaped URL) — see router.ts. When mounted at
// the un-namespaced path, redirectToNamespacedConsent below resolves the
// tenant from branding (already fetched by client_id, now carrying
// tenantSlug — see api/publicBranding.ts) and performs a client-side
// router.replace to the namespaced sibling, carrying the same query
// verbatim. The branding fetch is shared/cached by client_id
// (publicBranding.ts's module-level cache), so this costs no extra network
// round trip beyond what TenantBrandingProvider already does. If the slug
// can't be resolved for any reason, this silently stays put — consent still
// completes correctly at the un-namespaced path; the redirect is a URL-shape
// nicety, never a requirement for the flow to work.
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
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'
import TenantBrandingProvider, { type Branding } from '@/components/common/TenantBrandingProvider.vue'
import { fetchConsentContext, type ConsentContext, type ConsentScope } from '@/api/authConsentContext'
import { fetchBranding } from '@/api/publicBranding'

// client_id is parsed straight off the same captured query string used for
// the context-fetch below — one source of truth, matching LoginView.vue's
// established convention for the branding composable call.
const searchParams = new URLSearchParams(window.location.search)
const clientId = searchParams.get('client_id') ?? ''

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

// FE-2.10: client logo/initial per spec §6.2.8's wireframe — no client-logo
// capability exists anywhere in the system (only tenant branding has one),
// so the fallback the wireframe itself names ("logo or initial") is what
// this screen always renders: the first character of the client's name.
function clientInitial(clientName: string): string {
  return clientName.trim().charAt(0).toUpperCase() || '?'
}

const context = ref<ConsentContext | null>(null)
const isLoading = ref(true)
const error = ref('')

async function loadContext(): Promise<void> {
  isLoading.value = true
  error.value = ''
  const result = await fetchConsentContext(window.location.search)
  if (result.kind === 'ok') {
    context.value = result.value
  } else {
    error.value = 'Failed to load this authorization request.'
  }
  isLoading.value = false
}

const route = useRoute()
const router = useRouter()

// BE-B: see the two-hop redirect header comment above.
async function redirectToNamespacedConsent(): Promise<void> {
  if (typeof route.params.slug === 'string' && route.params.slug) return // already namespaced
  const result = await fetchBranding(clientId)
  if (result.kind === 'ok' && result.value.tenantSlug) {
    router.replace(`/t/${result.value.tenantSlug}/consent${window.location.search}`)
  }
}

onMounted(() => {
  loadContext()
  redirectToNamespacedConsent()
})

// requiresConsent=true scopes render as an unchecked-by-default checkbox the
// user must actively opt into. requiresConsent=false ("auto-grantable")
// scopes are shown for transparency but rendered as a hidden input on the
// approve form (always submitted) — the backend's own
// consentAutoGrantProviders customizer grants these unconditionally
// regardless of what's submitted, so this is purely cosmetic consistency
// between what's displayed and what's sent, not a security-relevant choice.
const autoGrantedScopes = computed(() => context.value?.scopes.filter((s) => !s.requiresConsent) ?? [])

// FE-2.10: alreadyGranted consumption. Built despite being empty through the
// BFF proxy in every production deployment today (see the plan's own
// "Measured ground truth" — the identity/session it would key off never
// reaches this endpoint via the BFF hop, a tracked architectural gap, not a
// bug here). The DTO field already exists on the wire, so this is the same
// "build ready-but-currently-inert plumbing ahead of the capability that
// activates it" pattern as registrationMode (FE-2c) and authorize_query
// (FE-2c/2d) — a stubbed non-empty array is the only way to exercise this
// path today, same as FE-2a's rate-limit test.
function isAlreadyGranted(scope: string): boolean {
  return context.value?.alreadyGranted.includes(scope) ?? false
}

// Defensive-only: ConsentScopeResolver never actually returns a null/blank
// description (confirmed by ConsentScopeResolverTest — it falls back to the
// raw scope string itself for every unknown-RS/unknown-scope case already).
// This is insurance against a future regression, not filling a real gap.
function scopeLabel(s: ConsentScope): string {
  return s.description || s.scope
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
        <!-- No client-logo capability exists anywhere in the system — the
             initial avatar is the wireframe's own named fallback. -->
        <div
          v-else-if="context?.clientName"
          class="flex h-10 w-10 items-center justify-center rounded-full bg-muted text-sm font-semibold text-foreground"
          aria-hidden="true"
        >
          {{ clientInitial(context.clientName) }}
        </div>
        <h1 class="text-xl font-semibold tracking-tight">
          {{ context?.clientName ? `${context.clientName} wants access to your account` : 'This application wants access to your account' }}
        </h1>
        <p class="text-sm text-muted-foreground">
          This app is requesting access to your {{ companyLabel(branding) }} account.
        </p>
      </div>
    </template>

    <p v-if="isLoading" class="text-sm text-muted-foreground text-center">Loading…</p>

    <p v-else-if="error" role="alert" class="text-sm text-destructive text-center">
      {{ error }}
    </p>

    <div v-else-if="context" class="flex flex-col gap-4">
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

        <!-- "Requested access" box (spec §6.2.8's wireframe): every scope,
             auto-granted or not, shown for transparency — description plus
             the raw scope string in mono, right-aligned. -->
        <div class="flex flex-col gap-3 rounded-lg border border-border p-4">
          <p class="text-xs font-medium uppercase tracking-wide text-muted-foreground">Requested access</p>

          <div
            v-for="s in context.scopes"
            :key="s.scope"
            class="flex items-start justify-between gap-3 text-sm"
          >
            <label v-if="s.requiresConsent" class="flex items-start gap-2">
              <input
                type="checkbox"
                name="scope"
                :value="s.scope"
                :checked="isAlreadyGranted(s.scope)"
                class="mt-0.5"
              >
              <span>
                {{ scopeLabel(s) }}
                <span v-if="isAlreadyGranted(s.scope)" class="text-xs text-muted-foreground">· Previously approved</span>
              </span>
            </label>
            <span v-else>{{ scopeLabel(s) }}</span>

            <span class="shrink-0 font-mono text-xs text-muted-foreground">{{ s.scope }}</span>
          </div>
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
  </AuthShell>
  </TenantBrandingProvider>
</template>
