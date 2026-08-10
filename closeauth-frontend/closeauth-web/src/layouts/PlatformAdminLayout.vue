<script setup lang="ts">
// Stage UI-4: the platform-admin console's shell — forked from
// TenantAdminLayout.vue for the same reason its own comment gives for not
// reusing AppSidebar/AdminLayout: this tree's nav shape and session type are
// genuinely different. Two differences worth calling out explicitly:
//   - No slug segment anywhere (header, sidebar, cookie) — this surface is
//     cross-tenant by construction.
//   - A visible token-expiry countdown, with standing copy that platform
//     sessions are short-lived (5 minutes) and never silently renewed —
//     the tenant console has nothing like this because AdminSession quietly
//     re-authorizes; PlatformSession never does (see platform_guard.go).
// No 'admin@closeauth.dev'-style placeholder fallback, matching
// TenantAdminLayout.vue's own explicit choice not to repeat that habit.
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import { LogOut, Moon, Sun } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import PlatformAdminSidebar from '@/components/app/PlatformAdminSidebar.vue'
import { useColorScheme } from '@/composables/useColorScheme'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

const store = usePlatformAdminSessionStore()
const { isDark, toggle } = useColorScheme()
const sidebarCollapsed = ref(false)

const email = computed(() => (store.state.kind === 'active' ? store.state.email : ''))
const roles = computed(() => (store.state.kind === 'active' ? store.state.roles : []))

// A live countdown to the access token's expiry — recomputed every second
// while mounted. Deliberately visible: the whole point of naming the
// 5-minute, non-renewing TTL in the UI is that an operator should never be
// surprised by a sudden session_expired mid-task.
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

const secondsRemaining = computed(() => {
  if (store.state.kind !== 'active') return 0
  const expiresAt = Date.parse(store.state.accessTokenExpiresAt)
  if (Number.isNaN(expiresAt)) return 0
  return Math.max(0, Math.round((expiresAt - now.value) / 1000))
})
const expiryLabel = computed(() => {
  const s = secondsRemaining.value
  if (s <= 0) return 'expired'
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${m}:${r.toString().padStart(2, '0')}`
})
const expiryUrgent = computed(() => secondsRemaining.value > 0 && secondsRemaining.value <= 60)

async function handleSignOut(): Promise<void> {
  await store.signOut()
  window.location.assign('/platform/login')
}
</script>

<template>
  <div class="min-h-screen bg-background text-foreground flex">
    <PlatformAdminSidebar v-model="sidebarCollapsed" />
    <div class="flex flex-col flex-1 min-w-0">
      <header class="h-14 flex items-center justify-between px-6 border-b border-border shrink-0">
        <div class="flex items-center gap-3">
          <span class="text-lg font-semibold tracking-tighter">CloseAuth</span>
          <span class="text-sm text-muted-foreground font-mono">/ platform</span>
        </div>
        <div class="flex items-center gap-4">
          <div v-if="email" class="flex flex-col items-end leading-tight">
            <span class="text-sm text-muted-foreground">{{ email }}</span>
            <span class="text-[11px] text-muted-foreground/70">{{ roles.join(', ') || 'no platform roles' }}</span>
          </div>
          <span
            id="platform-session-expiry"
            class="text-xs font-mono rounded px-1.5 py-0.5"
            :class="expiryUrgent ? 'text-destructive bg-destructive/10' : 'text-muted-foreground bg-muted'"
            title="Platform sessions are 5 minutes and are never silently renewed — sign in again once this reaches 0:00."
          >
            {{ expiryLabel }}
          </span>
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
