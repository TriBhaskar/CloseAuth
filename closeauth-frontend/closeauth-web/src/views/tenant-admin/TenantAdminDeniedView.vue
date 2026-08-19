<script setup lang="ts">
// Stage UI-3a: the callback-refusal page. Reached when a callback's binding
// checks fail or when a denial marker short-circuits a repeat /admin/login
// visit (see internal/server/handlers_admin_auth.go's "loop break A").
//
// FE-4d: this page is no longer reached for "authenticated but not
// TENANT_ADMIN" — that case now gets a real session and lands on /account
// instead (spec §6.4.8). The remaining, much rarer reason
// (invalid_client_binding) is a genuine integrity failure: the token's
// client_id/tenant_id doesn't match this tenant's admin-console client at
// all — essentially unreachable in honest use. not_tenant_admin is STILL a
// valid reason here too, but from a different, unrelated source: an
// already-established admin session whose TENANT_ADMIN role gets revoked
// mid-session (admin_api_result.go's writeAdminAPIResult) — that
// mid-session re-verification is untouched by this session's fix.
//
// Deliberately lives OUTSIDE the guarded route subtree (see
// router/index.ts) and does NOTHING automatically on mount — no
// auto-redirect, no auto-retry. That is the loop fix: a refusal page that
// itself re-triggered auth would recreate exactly the loop this stage
// exists to prevent. The only way to retry is the explicit "try a different
// account" action below, which dismisses the denial marker first.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'
import { dismissDenial } from '@/api/tenantAdminSession'

const route = useRoute()
const slug = computed(() => String(route.params.slug ?? ''))
const reason = computed(() => {
  const raw = route.query.reason
  return typeof raw === 'string' ? raw : 'not_tenant_admin'
})

const MESSAGES: Record<string, string> = {
  not_tenant_admin:
    'You signed in successfully, but this account does not have TENANT_ADMIN access to this tenant.',
  invalid_client_binding:
    'Something is wrong with this sign-in — the account or client does not match this tenant. Try signing in again.',
}

const message = computed(() => MESSAGES[reason.value] ?? MESSAGES.not_tenant_admin)

async function handleTryDifferentAccount(): Promise<void> {
  await dismissDenial(slug.value)
  // A real top-level navigation, not a router push: /admin/login is a BFF
  // route (internal/server/handlers_admin_auth.go), not a client-side one.
  window.location.assign(`/t/${slug.value}/admin/login`)
}
</script>

<template>
  <AuthShell>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <h1 class="text-xl font-semibold tracking-tight">Access denied</h1>
      </div>
    </template>

    <div class="flex flex-col gap-4 text-center">
      <p role="alert" class="text-sm text-foreground">{{ message }}</p>
      <Button id="tenant-admin-denied-retry" variant="outline" class="w-full" @click="handleTryDifferentAccount">
        Try a different account
      </Button>
      <RouterLink to="/" class="text-sm text-muted-foreground hover:underline">Back to home</RouterLink>
    </div>
  </AuthShell>
</template>
