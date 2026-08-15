<script setup lang="ts">
// Stage UI-3a: the landing page proving the tenant-admin login loop works
// end to end — session data from the store (populated by the router guard's
// fetchSession call before this view ever renders) plus a live call to the
// real backend gate (GET /t/{slug}/api/ping -> GET /v1/tenants/{id}/admin-ping).
// Honors stores/admin.ts's standing rule: no data here is ever fabricated —
// a failed ping shows a visible [role="alert"], never a silent fallback.
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'
import { fetchPing, type TenantAdminPingResult } from '@/api/tenantAdminPing'

const route = useRoute()
const slug = String(route.params.slug ?? '')
const store = useTenantAdminSessionStore()

const pingResult = ref<TenantAdminPingResult | null>(null)
const isPinging = ref(true)

onMounted(async () => {
  isPinging.value = true
  pingResult.value = await fetchPing(slug)
  isPinging.value = false
})

function handleSignOut(): void {
  store.signOut()
}
</script>

<template>
  <div class="max-w-2xl mx-auto flex flex-col gap-6">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Tenant admin console</h1>
      <p class="text-sm text-muted-foreground">Foundation stage — the login loop is wired; the dashboard comes later.</p>
    </div>

    <div v-if="store.state.kind === 'active'" class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        <dt class="text-muted-foreground">Tenant slug</dt>
        <dd id="tenant-admin-home-slug" class="font-mono">{{ slug }}</dd>

        <dt class="text-muted-foreground">Signed in as</dt>
        <dd id="tenant-admin-home-email">{{ store.state.email }}</dd>

        <dt class="text-muted-foreground">Roles</dt>
        <dd id="tenant-admin-home-roles" class="flex gap-1 flex-wrap">
          <Badge v-for="role in store.state.tenantRoles" :key="role" variant="secondary">{{ role }}</Badge>
        </dd>

        <dt class="text-muted-foreground">Access token expires</dt>
        <dd id="tenant-admin-home-expires" class="font-mono text-xs">{{ store.state.accessTokenExpiresAt }}</dd>
      </dl>

      <div id="tenant-admin-home-ping" class="rounded-lg bg-muted p-4 text-sm">
        <template v-if="isPinging">Checking backend access…</template>
        <template v-else-if="pingResult?.kind === 'ok'">
          <span class="text-foreground">
            ✓ Backend confirmed tenant-admin access to <span class="font-mono">{{ pingResult.tenantId }}</span>
          </span>
        </template>
        <template v-else-if="pingResult?.kind === 'reauth'"> Re-authorizing… </template>
        <template v-else>
          <p role="alert" class="text-destructive">
            {{ pingResult?.kind === 'error' ? pingResult.message : 'Could not reach the backend.' }}
          </p>
        </template>
      </div>

      <Button id="tenant-admin-home-signout" variant="outline" class="self-start" @click="handleSignOut">
        Sign out
      </Button>
    </div>

    <div v-else class="rounded-xl border border-border p-6">
      <p class="text-sm text-muted-foreground">Loading your session…</p>
    </div>
  </div>
</template>
