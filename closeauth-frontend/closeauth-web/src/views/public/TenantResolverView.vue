<script setup lang="ts">
// FE-2a (spec §6.2.1): the tenant resolver at /t/:slug — replaces the bare
// `redirect: (to) => \`/t/${to.params.slug}/console\`` shortcut this route
// used to be (a console-navigation convenience, not a real resolver —
// FRONTEND_INVENTORY.md graded it Replace for exactly that reason).
//
// No UI beyond a loading indicator with the tenant logo once branding
// resolves (spec's own words: "full-page spinner"). This build uses a
// static, motion-free loading indicator rather than a CSS spin animation —
// §3.7's motion budget (already enforced elsewhere, e.g. AuthLayout's
// entrance-animation removal in FE-1a) allows only the two named 120/180ms
// transitions and nothing else; a spinning icon would be exactly the kind
// of decorative motion that rule exists to forbid. aria-live carries the
// same "something is happening" signal accessibly instead.
//
// Logic (spec §6.2.1, verbatim):
//   1. Resolve the tenant (reusing entryResolve.ts — the SAME existence
//      check the entry screen calls, per this session's decision #4: this
//      route is reachable directly, not only via `/`, so it needs its own
//      existence check too, and it doubles as the only way to get a
//      displayName/derive a client_id for branding).
//   2. Unknown/suspended (or unreachable) -> /t/{tenantId}/auth-error,
//      UNBRANDED (there is no branding to trust before step 1 succeeds).
//   3. Once resolved: fetch branding (TenantBrandingProvider) and ask the
//      BFF for session state (fetchSession — the SAME admin-session probe
//      the tenant-admin console guard already uses).
//   4. Live session + TENANT_ADMIN -> /t/{tenantId}/console. Live session,
//      not an admin -> /t/{tenantId}/account. FE-4d: this used to be
//      "directionally correct, not exact" (the old fetchSession probe
//      couldn't distinguish "no session" from "valid non-admin session" —
//      a non-admin was refused a session outright at the callback). Fixed:
//      handleAdminCallback now creates a real session for any authenticated
//      tenant user, so 'active' here genuinely means "has a session" and
//      the role check below is exact, not a guess. No session/reauth/
//      unreachable -> a fresh POST /api/auth/authorize/start, then a REAL
//      top-level navigation to the returned URL (never router.push, never
//      fetch-followed — see authorizeStart.ts's own header for why).
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import EntryShell from '@/shells/EntryShell.vue'
import TenantBrandingProvider from '@/components/common/TenantBrandingProvider.vue'
import { resolveTenant } from '@/api/entryResolve'
import { fetchSession } from '@/api/tenantAdminSession'
import { startAuthorize } from '@/api/authorizeStart'

const route = useRoute()
const router = useRouter()

const tenantId = String(route.params.slug ?? '')
// admin-console-{tenantId}: the SAME deterministic client id
// AdminConsoleClientProvisioningCallback (Java) derives server-side at
// provisioning time — no new branding-by-tenantId endpoint needed, since
// /branding already resolves by client_id.
const clientId = `admin-console-${tenantId}`

const resolved = ref(false)

function toAuthError(reason: string): void {
  router.replace(`/t/${tenantId}/auth-error?reason=${reason}`)
}

onMounted(async () => {
  const resolution = await resolveTenant(tenantId)
  if (resolution.kind !== 'ok') {
    toAuthError(resolution.kind === 'notFound' ? 'unknown_tenant' : 'bff_unreachable')
    return
  }
  resolved.value = true

  const session = await fetchSession(tenantId)
  if (session.kind === 'active') {
    const destination = session.tenantRoles.includes('TENANT_ADMIN') ? 'console' : 'account'
    router.replace(`/t/${tenantId}/${destination}`)
    return
  }
  if (session.kind === 'denied') {
    router.replace(`/t/${tenantId}/denied?reason=${session.reason}`)
    return
  }

  // anonymous / reauth / unreachable: begin a fresh authorization request.
  const start = await startAuthorize(tenantId)
  if (start.kind === 'ok') {
    window.location.href = start.authorizeUrl
    return
  }
  if (start.kind === 'notFound') {
    toAuthError('unknown_tenant')
  } else if (start.kind === 'rateLimited') {
    toAuthError('rate_limited')
  } else {
    toAuthError('bff_unreachable')
  }
})
</script>

<template>
  <TenantBrandingProvider v-if="resolved" :client-id="clientId" v-slot="{ branding, hasLogo }">
    <AuthShell>
      <div class="flex flex-col items-center gap-4 text-center">
        <!-- FE-6.5: reserved box — see LoginView.vue's identical fix. -->
        <div class="h-10">
          <img
            v-if="hasLogo"
            :src="branding.logoUrl"
            :alt="branding.companyName"
            class="h-10 w-auto object-contain"
          />
        </div>
        <p class="text-sm text-muted-foreground" aria-live="polite">Signing you in…</p>
      </div>
    </AuthShell>
  </TenantBrandingProvider>
  <EntryShell v-else>
    <div class="flex flex-col items-center gap-4 text-center">
      <p class="text-sm text-muted-foreground" aria-live="polite">Loading your workspace…</p>
    </div>
  </EntryShell>
</template>
