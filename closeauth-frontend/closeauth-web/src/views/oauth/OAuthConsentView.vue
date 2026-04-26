<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Check,
  Eye,
  Info,
  KeyRound,
  Loader2,
  Mail,
  PenLine,
  RefreshCw,
  Shield,
  User,
} from 'lucide-vue-next'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { useAsyncState } from '@/composables/useAsyncState'
import { oauthService } from '@/api/services'
import { adminService } from '@/api/services'

const route = useRoute()

// ── Composables ────────────────────────────────────────────────────────────────
const { clientId, clientName, clientLogoUrl, loadTheme } = useOAuthTheme()
const { isLoading, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const username = ref('')
const userEmail = ref('')
const scopes = ref<string[]>([])
const state = ref('')
const redirectUri = ref('')

// ── Scope metadata ─────────────────────────────────────────────────────────────
type ScopeInfo = { label: string; description: string; icon: typeof Shield }

const scopeMap: Record<string, ScopeInfo> = {
  openid:         { label: 'Sign you in',        description: 'Verify your identity',          icon: KeyRound  },
  profile:        { label: 'View your profile',   description: 'Access your name and picture',  icon: User      },
  email:          { label: 'Access your email',   description: 'Know your email address',       icon: Mail      },
  offline_access: { label: 'Stay signed in',      description: "Access when you're not active", icon: RefreshCw },
  'read:users':   { label: 'Read users',          description: 'List and view user data',       icon: Eye       },
  'write:users':  { label: 'Manage users',        description: 'Create and modify users',       icon: PenLine   },
}

const scopeInfo = computed(() =>
  scopes.value.map((scope) => ({
    scope,
    ...(scopeMap[scope] ?? { label: scope, description: 'Access requested', icon: Shield }),
  })),
)

// ── On mount ───────────────────────────────────────────────────────────────────
onMounted(async () => {
  state.value       = (route.query.state        as string) ?? ''
  redirectUri.value = (route.query.redirect_uri as string) ?? ''
  const rawScope    = (route.query.scope        as string) ?? ''
  scopes.value      = rawScope ? rawScope.split(' ').filter(Boolean) : []

  await loadTheme()

  // Fetch current user
  try {
    const user = await adminService.getMe()
    username.value  = user.username
    userEmail.value = user.email
  } catch { /* silently fail — consent still works without user display */ }
})

// ── Handlers ───────────────────────────────────────────────────────────────────
const handleAllow = async () => {
  const result = await execute(() =>
    oauthService.submitConsent({
      action: 'approve',
      client_id: clientId.value,
      state: state.value,
      redirect_uri: redirectUri.value,
      scopes: scopes.value,
    }),
  )
  if (result?.redirect_url) window.location.href = result.redirect_url
}

const handleDeny = async () => {
  const result = await execute(() =>
    oauthService.submitConsent({
      action: 'deny',
      client_id: clientId.value,
      state: state.value,
      redirect_uri: redirectUri.value,
    }),
  )
  if (result?.redirect_url) window.location.href = result.redirect_url
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- 1. Header block -->
    <div class="text-center flex flex-col items-center gap-3">
      <!-- Logo -->
      <img
        v-if="clientLogoUrl"
        :src="clientLogoUrl"
        alt="App logo"
        class="h-12 w-12 object-contain rounded-md"
      />
      <div
        v-else
        class="h-12 w-12 rounded-md flex items-center justify-center bg-primary"
        style="background-color: var(--theme-button)"
      >
        <span class="text-primary-foreground text-xl font-semibold">
          {{ clientName.charAt(0).toUpperCase() }}
        </span>
      </div>

      <div>
        <p class="text-lg font-semibold text-foreground">{{ clientName }}</p>
        <p class="text-sm text-muted-foreground">by {{ clientName }}</p>
      </div>
    </div>

    <!-- 2. User identity block -->
    <div class="border border-border rounded-lg p-3 bg-muted/30">
      <div class="flex items-center gap-3">
        <div class="h-9 w-9 rounded-full bg-muted flex items-center justify-center shrink-0">
          <User class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="min-w-0">
          <p class="text-sm font-medium text-foreground truncate">
            {{ username || 'You' }}
          </p>
          <p v-if="userEmail" class="text-xs text-muted-foreground truncate">{{ userEmail }}</p>
        </div>
      </div>
    </div>

    <!-- 3. Consent message -->
    <p class="text-sm text-muted-foreground text-center">
      <span class="font-medium text-foreground">{{ clientName }}</span> is requesting access to your account
    </p>

    <!-- 4. Scope section label -->
    <div>
      <p class="text-[11px] font-semibold uppercase tracking-widest text-muted-foreground mb-3">
        Permissions Requested
      </p>

      <!-- 5. Scope list -->
      <div class="space-y-2">
        <div
          v-for="{ scope, label, description, icon: ScopeIcon } in scopeInfo"
          :key="scope"
          class="border border-border rounded-md p-3 flex items-start gap-3 bg-card"
        >
          <!-- Icon circle -->
          <div
            class="h-8 w-8 rounded-md flex items-center justify-center shrink-0"
            style="background-color: color-mix(in oklch, var(--theme-button) 10%, transparent)"
          >
            <component
              :is="ScopeIcon"
              class="h-4 w-4 text-primary"
              style="color: var(--theme-button)"
            />
          </div>

          <!-- Text -->
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-foreground">{{ label }}</p>
            <p class="text-xs text-muted-foreground mt-0.5">{{ description }}</p>
          </div>

          <!-- Check -->
          <Check
            class="h-4 w-4 shrink-0 mt-0.5 text-primary"
            style="color: var(--theme-button)"
          />
        </div>
      </div>
    </div>

    <!-- 6. Info notice -->
    <div class="bg-blue-50 border border-blue-100 rounded-md px-3 py-2 flex items-center gap-2">
      <Info class="h-3.5 w-3.5 text-blue-400 shrink-0" />
      <p class="text-xs text-blue-700">
        You can revoke access at any time from your account settings.
      </p>
    </div>

    <!-- 7. Action buttons -->
    <div class="space-y-2">
      <button
        type="button"
        class="w-full h-10 rounded-md bg-green-600 hover:bg-green-700 text-white text-sm font-semibold active:scale-[0.98] transition-all disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2"
        :disabled="isLoading"
        @click="handleAllow"
      >
        <Loader2 v-if="isLoading" class="h-4 w-4 animate-spin" />
        {{ isLoading ? 'Authorizing…' : 'Allow access' }}
      </button>

      <button
        type="button"
        class="w-full h-10 rounded-md bg-card border border-border text-foreground text-sm hover:bg-muted transition-colors active:scale-[0.98]"
        :disabled="isLoading"
        @click="handleDeny"
      >
        Deny
      </button>

      <p class="text-xs text-muted-foreground text-center pt-1">
        By allowing, you agree to share the listed information.
      </p>
    </div>
  </div>
</template>
