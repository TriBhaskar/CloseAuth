<script setup lang="ts">
// Stage UI-4: a deliberately thin landing page — who you're signed in as,
// your roles, token expiry, and links to the two real surfaces. NOT a
// dashboard: no counts, no charts, no analytics. GET /v1/platform/audit-events
// exists on the backend but is deliberately unused here — cross-tenant audit
// is explicitly out of this stage's scope (see the stage plan's §4).
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { Building2, Users2 } from 'lucide-vue-next'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

const store = usePlatformAdminSessionStore()
const email = computed(() => (store.state.kind === 'active' ? store.state.email : ''))
const roles = computed(() => (store.state.kind === 'active' ? store.state.roles : []))
</script>

<template>
  <div class="flex flex-col gap-6 max-w-2xl">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Platform console</h1>
      <p class="text-sm text-muted-foreground">
        Signed in as <span class="font-medium text-foreground">{{ email }}</span> with roles
        <span class="font-mono text-xs">{{ roles.join(', ') || 'none' }}</span>.
      </p>
    </div>

    <p class="text-sm text-muted-foreground">
      This console is deliberately small — routine tenant and platform-admin operations only.
      There is no cross-tenant audit view, no analytics, and no tenant impersonation here.
      Platform sessions are 5 minutes and are never silently renewed — sign in again once yours expires.
    </p>

    <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
      <RouterLink
        to="/platform/console/tenants"
        class="rounded-xl border border-border p-5 flex items-start gap-3 hover:border-primary/40 transition-colors"
      >
        <Building2 class="h-5 w-5 shrink-0 text-muted-foreground" />
        <div>
          <h2 class="text-sm font-semibold">Tenants</h2>
          <p class="text-xs text-muted-foreground">Provision, activate, and suspend tenants.</p>
        </div>
      </RouterLink>
      <RouterLink
        to="/platform/console/admins"
        class="rounded-xl border border-border p-5 flex items-start gap-3 hover:border-primary/40 transition-colors"
      >
        <Users2 class="h-5 w-5 shrink-0 text-muted-foreground" />
        <div>
          <h2 class="text-sm font-semibold">Platform admins</h2>
          <p class="text-xs text-muted-foreground">Create CloseAuth staff admins and manage their roles.</p>
        </div>
      </RouterLink>
    </div>
  </div>
</template>
