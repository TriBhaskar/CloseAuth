<script setup lang="ts">
// Stage UI-3a: the dead-end error page for the admin-console login/reauth
// flow's non-refusal failure modes (invalid/tampered state, an unprovisioned
// tenant, a pathological repeated-attempt loop, the BFF being unreachable,
// or the backend cancelling the flow). Distinct from
// TenantAdminDeniedView.vue, which is specifically "authenticated but not
// TENANT_ADMIN" — this page covers everything else that can't reach a
// session.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'

const route = useRoute()
const slug = computed(() => String(route.params.slug ?? ''))
const reason = computed(() => {
  const raw = route.query.reason
  return typeof raw === 'string' ? raw : 'unknown'
})

const MESSAGES: Record<string, string> = {
  invalid_state: "We couldn't verify your sign-in attempt. Please try again.",
  invalid_slug: 'That tenant address is not valid.',
  unknown_tenant: 'This tenant does not have an admin console configured yet.',
  token_exchange_failed: "We couldn't complete your sign-in attempt. Please try again.",
  bff_unreachable: 'The admin console could not reach its backend. Please try again shortly.',
  access_denied: 'You cancelled the sign-in attempt.',
  // FE-2a: the tenant resolver's own rate limiter (routes.go's
  // authorizeStartLimiter) rejected the sign-in attempt. Retryable — unlike
  // login_loop below, this is a transient window, not a structural problem.
  rate_limited: 'Too many attempts. Please wait a moment and try again.',
  // Deliberately NOT retryable from here (see showRetry below) — reaching
  // this reason means /admin/login or /admin/reauth was hit repeatedly
  // without a successful callback, a structural problem a retry link would
  // just repeat.
  login_loop:
    'Something is preventing sign-in from completing. Please contact support if this keeps happening.',
}

const message = computed(() => MESSAGES[reason.value] ?? 'Something went wrong while signing in.')
const showRetry = computed(
  () => reason.value !== 'login_loop' && slug.value !== '' && slug.value !== 'unknown',
)
</script>

<template>
  <AuthShell>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <h1 class="text-xl font-semibold tracking-tight">Sign-in problem</h1>
      </div>
    </template>

    <div class="flex flex-col gap-4 text-center">
      <p role="alert" class="text-sm text-foreground">{{ message }}</p>
      <a v-if="showRetry" :href="`/t/${slug}/admin/login`">
        <Button variant="outline" class="w-full">Try again</Button>
      </a>
      <RouterLink to="/" class="text-sm text-muted-foreground hover:underline"
        >Back to home</RouterLink
      >
    </div>
  </AuthShell>
</template>
