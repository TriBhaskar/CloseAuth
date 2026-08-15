<script setup lang="ts">
// Stage UI-3a built this shell RouterView-only, deliberately WITHOUT a
// sidebar: with exactly one destination (the console landing page), porting
// AppSidebar.vue — whose nav items are hardcoded `/admin/*` constants under
// a "Platform" label — would have been premature (and wrong: this is the
// TENANT_ADMIN tree, not the platform one). UI-3a's comment here said "fork
// or parameterize AppSidebar when there's real navigation to show."
//
// Stage UI-3b: there is now (Users, with clients/roles/branding/audit to
// follow) — forked into TenantAdminSidebar.vue rather than parameterizing
// AppSidebar, for the same reason UI-3a gave: AppSidebar's nav shape is
// platform-specific and it's currently used only by the orphaned, unrouted
// AdminLayout.vue, so a shared-nav abstraction would be speculative.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { LogOut, Moon, Sun } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import TenantAdminSidebar from '@/components/app/TenantAdminSidebar.vue'
import { useColorScheme } from '@/composables/useColorScheme'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'

const route = useRoute()
const slug = computed(() => String(route.params.slug ?? ''))
const store = useTenantAdminSessionStore()
const { isDark, toggle } = useColorScheme()
const sidebarCollapsed = ref(false)

// No 'admin@closeauth.dev'-style fallback (a habit of the orphaned
// AdminLayout.vue this deliberately does not repeat) — an unauthenticated
// email is simply not shown, never a fabricated placeholder.
const email = computed(() => (store.state.kind === 'active' ? store.state.email : ''))

function handleSignOut(): void {
  store.signOut()
}
</script>

<template>
  <div class="min-h-screen bg-background text-foreground flex">
    <TenantAdminSidebar v-model="sidebarCollapsed" :slug="slug" />
    <div class="flex flex-col flex-1 min-w-0">
      <header class="h-14 flex items-center justify-between px-6 border-b border-border shrink-0">
        <div class="flex items-center gap-3">
          <span class="text-lg font-semibold tracking-tighter">CloseAuth</span>
          <span class="text-sm text-muted-foreground font-mono">/ {{ slug }}</span>
        </div>
        <div class="flex items-center gap-3">
          <span v-if="email" class="text-sm text-muted-foreground">{{ email }}</span>
          <Button variant="ghost" size="icon-sm" aria-label="Toggle dark mode" @click="toggle">
            <Moon v-if="!isDark" class="size-4" />
            <Sun v-else class="size-4" />
          </Button>
          <Button variant="ghost" size="sm" @click="handleSignOut">
            <LogOut class="size-4" />
            Sign out
          </Button>
        </div>
      </header>
      <main class="flex-grow p-6 overflow-auto">
        <RouterView />
      </main>
    </div>
  </div>
</template>
