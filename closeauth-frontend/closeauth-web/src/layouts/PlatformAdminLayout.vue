<script setup lang="ts">
// FE-1.10: thin composition root over the shared shells/ConsoleShell.vue —
// this file now owns only what's genuinely platform-console-specific (nav
// items, the session store, the token-expiry countdown, sign-out).
//
// The countdown is deliberately kept here rather than pushed into
// ConsoleShell: it's specific to PlatformSession's 5-minute, non-renewing
// TTL (platform_guard.go) — the tenant console has nothing analogous
// (AdminSession quietly re-authorizes instead) — and ConsoleShell shouldn't
// need to know which principal type is signed in.
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Building2, LogOut, ShieldAlert, Users2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import ConsoleShell from '@/shells/ConsoleShell.vue'
import FormField from '@/components/common/FormField.vue'
import type { ConsoleNavItem } from '@/shells/ConsoleNavList.vue'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'
import { login } from '@/api/platformAdminSession'

const store = usePlatformAdminSessionStore()

const email = computed(() => (store.state.kind === 'active' ? store.state.email : ''))
const roles = computed(() => (store.state.kind === 'active' ? store.state.roles : []))

const navItems: ConsoleNavItem[] = [
  { label: 'Tenants', icon: Building2, path: '/platform/console/tenants' },
  { label: 'Platform admins', icon: Users2, path: '/platform/console/admins' },
]

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

// FE-3a (spec §6.3.1): "on expiry, an interstitial re-authentication dialog
// appears over the current page". Fires exactly once per crossing into 0:00
// (a plain value-change watch, not a per-tick check) — store.requestReauth
// itself is also idempotent (an 'expired' value is never downgraded), so a
// second call here would be harmless either way, this just avoids making it.
watch(
  secondsRemaining,
  (s) => {
    if (s <= 0 && store.state.kind === 'active') store.requestReauth('expired')
  },
  { immediate: true },
)

async function handleSignOut(): Promise<void> {
  await store.signOut()
  window.location.assign('/platform/login')
}

// ---- FE-3a: the in-place re-authentication overlay --------------------
// Deliberately NOT the shared Dialog component (see the plan's own
// reasoning) — it must reliably sit above an already-open Dialog (e.g. a
// Suspend confirmation left open when the session expires) without fighting
// that dialog's own focus trap. A plain fixed-position overlay never
// unmounts the page underneath it, which is also what makes "preserved
// in-flight dialog state" true for free: nothing underneath it ever tears down.
const reauthEmail = ref('')
const reauthPassword = ref('')
const reauthSubmitting = ref(false)
const reauthError = ref('')

async function handleReauth(): Promise<void> {
  if (reauthSubmitting.value) return
  reauthError.value = ''
  reauthSubmitting.value = true
  try {
    const result = await login(reauthEmail.value, reauthPassword.value)
    switch (result.kind) {
      case 'ok':
        store.completeReauth(result.state)
        reauthEmail.value = ''
        reauthPassword.value = ''
        break
      case 'invalidCredentials':
        // Same enumeration-safe copy as PlatformLoginView.vue's own handler.
        reauthError.value = 'Incorrect email or password. Please try again.'
        break
      case 'notPlatformAdmin':
        reauthError.value = 'This account exists but does not hold PLATFORM_ADMIN, so it cannot use this console.'
        break
      case 'unreachable':
        reauthError.value = 'Could not reach the server. Please try again.'
        break
    }
  } finally {
    reauthSubmitting.value = false
  }
}
</script>

<template>
  <ConsoleShell :nav-items="navItems" :mark-icon="ShieldAlert" identity-label="PLATFORM">
    <template #topbar-actions>
      <div v-if="email" class="flex flex-col items-end leading-tight">
        <span class="text-sm text-muted-foreground">{{ email }}</span>
        <span class="text-[11px] text-muted-foreground/70">{{ roles.join(', ') || 'no platform roles' }}</span>
      </div>
      <span
        v-if="!expiryUrgent"
        id="platform-session-expiry"
        class="text-xs font-mono rounded px-1.5 py-0.5 text-muted-foreground bg-muted"
        title="Platform sessions are 5 minutes and are never silently renewed — sign in again once this reaches 0:00."
      >
        {{ expiryLabel }}
      </span>
      <span
        v-else
        id="platform-session-expiry"
        class="text-xs font-mono rounded px-1.5 py-0.5 text-destructive bg-destructive/10"
      >
        Session ends in {{ expiryLabel }} ·
        <button
          type="button"
          class="underline hover:no-underline"
          @click="store.requestReauth('proactive')"
        >
          Stay signed in
        </button>
      </span>
      <Button variant="ghost" size="sm" @click="handleSignOut">
        <LogOut class="size-4" />
        Sign out
      </Button>
    </template>
    <RouterView />

    <!-- FE-3a: the in-place re-auth overlay — see the script's own header
         comment for why this is hand-rolled rather than the shared Dialog. -->
    <div
      v-if="store.needsReauth"
      class="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="reauth-title"
    >
      <div class="w-full max-w-sm rounded-lg border border-border bg-surface p-6 flex flex-col gap-4">
        <div>
          <h2 id="reauth-title" class="text-lg font-semibold">
            {{ store.needsReauth === 'expired' ? 'Session expired' : `Session ends in ${expiryLabel}` }}
          </h2>
          <p class="text-sm text-muted-foreground">
            {{
              store.needsReauth === 'expired'
                ? "Sign in again to continue. Nothing you had open has been lost."
                : 'Sign in again now to keep working without interruption.'
            }}
          </p>
        </div>

        <form class="flex flex-col gap-3" novalidate @submit.prevent="handleReauth">
          <FormField id="platform-reauth-email" label="Email">
            <template #default="{ hasError, describedBy }">
              <Input
                id="platform-reauth-email"
                v-model="reauthEmail"
                type="email"
                autocomplete="email"
                required
                :disabled="reauthSubmitting"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>
          <FormField id="platform-reauth-password" label="Password">
            <template #default="{ hasError, describedBy }">
              <Input
                id="platform-reauth-password"
                v-model="reauthPassword"
                type="password"
                autocomplete="current-password"
                required
                :disabled="reauthSubmitting"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <p v-if="reauthError" role="alert" class="text-sm text-destructive">{{ reauthError }}</p>

          <div class="flex gap-2">
            <Button type="submit" class="flex-1" :disabled="reauthSubmitting">
              {{ reauthSubmitting ? 'Signing in…' : 'Sign in again' }}
            </Button>
            <Button
              v-if="store.needsReauth === 'proactive'"
              type="button"
              variant="ghost"
              @click="store.dismissReauth()"
            >
              Not now
            </Button>
          </div>
        </form>

        <button
          v-if="store.needsReauth === 'expired'"
          type="button"
          class="text-sm text-center text-muted-foreground hover:underline"
          @click="handleSignOut"
        >
          Sign out
        </button>
      </div>
    </div>
  </ConsoleShell>
</template>
