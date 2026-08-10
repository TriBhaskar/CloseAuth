<script setup lang="ts">
// Stage UI-3a: the non-admin refusal page. Reached when a callback's
// verification triple fails (authenticated, but no TENANT_ADMIN on this
// tenant — authorization ≠ authentication) or when a denial marker
// short-circuits a repeat /admin/login visit (see
// internal/server/handlers_admin_auth.go's "loop break A").
//
// Deliberately lives OUTSIDE the guarded route subtree (see
// router/index.ts) and does NOTHING automatically on mount — no
// auto-redirect, no auto-retry. That is the loop fix: a refusal page that
// itself re-triggered auth would recreate exactly the loop this stage
// exists to prevent. The only way to retry is the explicit "try a different
// account" action below, which dismisses the denial marker first.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AuthLayout from '@/layouts/AuthLayout.vue'
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
  <AuthLayout>
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
  </AuthLayout>
</template>
