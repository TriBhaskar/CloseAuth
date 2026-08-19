<script setup lang="ts">
// FE-1.10: thin composition root over the shared shells/ConsoleShell.vue —
// this file now owns only what's genuinely tenant-console-specific (nav
// items, the session store, sign-out), not shell/responsive-layout logic,
// which moved into ConsoleShell once it stopped needing to be duplicated
// against layouts/PlatformAdminLayout.vue.
//
// FE-4d: this layout is now shared by BOTH /console (admin-only content)
// and /account (spec §6.4.8 — every tenant user, admin or not). navItems
// becomes role-conditional rather than a second layout being invented: an
// admin sees the existing full nav plus a new "My account" item; a
// non-admin sees only "My account" (nothing else is reachable to them
// regardless — every /console/* route redirects a non-admin to /account
// via requiresTenantAdmin, so listing those items would be a dead end).
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { KeyRound, LogOut, ScrollText, ServerCog, Settings, ShieldCheck, ShieldEllipsis, User, Users } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import ConsoleShell from '@/shells/ConsoleShell.vue'
import type { ConsoleNavItem } from '@/shells/ConsoleNavList.vue'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'

const route = useRoute()
const slug = computed(() => String(route.params.slug ?? ''))
const store = useTenantAdminSessionStore()

// No 'admin@closeauth.dev'-style fallback (a habit of the orphaned
// AdminLayout.vue this deliberately does not repeat) — an unauthenticated
// email is simply not shown, never a fabricated placeholder.
const email = computed(() => (store.state.kind === 'active' ? store.state.email : ''))

const isTenantAdmin = computed(
  () => store.state.kind === 'active' && store.state.tenantRoles.includes('TENANT_ADMIN'),
)

// FE-4a (spec §4.2): grouped nav — DIRECTORY / APPLICATIONS / OPERATIONS.
// Order within OPERATIONS also changed to match spec's own wireframe
// (Audit log, then Settings — previously Settings, then Audit log).
// FE-4d: "My account" is always present (admin or not); the rest is
// admin-only.
const navItems = computed<ConsoleNavItem[]>(() => {
  const accountItem: ConsoleNavItem = { label: 'My account', icon: User, path: `/t/${slug.value}/account` }
  if (!isTenantAdmin.value) return [accountItem]
  return [
    { label: 'Users', icon: Users, path: `/t/${slug.value}/console/users`, group: 'Directory' },
    { label: 'Clients', icon: KeyRound, path: `/t/${slug.value}/console/clients`, group: 'Applications' },
    {
      label: 'Resource servers',
      icon: ServerCog,
      path: `/t/${slug.value}/console/resource-servers`,
      group: 'Applications',
    },
    { label: 'Roles', icon: ShieldEllipsis, path: `/t/${slug.value}/console/roles`, group: 'Applications' },
    { label: 'Audit log', icon: ScrollText, path: `/t/${slug.value}/console/audit`, group: 'Operations' },
    { label: 'Settings', icon: Settings, path: `/t/${slug.value}/console/settings`, group: 'Operations' },
    accountItem,
  ]
})

function handleSignOut(): void {
  store.signOut()
}
</script>

<template>
  <ConsoleShell
    :nav-items="navItems"
    :mark-icon="ShieldCheck"
    identity-label="Console"
    :identity-tenant-id="slug"
  >
    <template #topbar-actions>
      <span v-if="email" class="text-sm text-muted-foreground">{{ email }}</span>
      <Button variant="ghost" size="sm" @click="handleSignOut">
        <LogOut class="size-4" />
        Sign out
      </Button>
    </template>
    <RouterView />
  </ConsoleShell>
</template>
