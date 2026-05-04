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
import { getCsrfToken } from '@/api/client'

const route = useRoute()

// ── Composables ────────────────────────────────────────────────────────────────
const { clientId, clientName, clientLogoUrl, loadTheme } = useOAuthTheme()

// ── State ──────────────────────────────────────────────────────────────────────
const username = ref('')
const userEmail = ref('')
const scopes = ref<string[]>([])
const state = ref('')
const csrfToken = ref('')
const isLoading = ref(true)

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

// ── On mount: fetch consent data from Go API ──────────────────────────────────
onMounted(async () => {
  // Build query string from route params
  const queryParams = new URLSearchParams()
  if (route.query.client_id) queryParams.set('client_id', route.query.client_id as string)
  if (route.query.scope) queryParams.set('scope', route.query.scope as string)
  if (route.query.state) queryParams.set('state', route.query.state as string)

  await loadTheme()

  try {
    const resp = await fetch(`/api/oauth/consent-data?${queryParams.toString()}`, {
      credentials: 'include',
    })
    if (resp.ok) {
      const data = await resp.json()
      if (data.client_name) clientName.value = data.client_name
      username.value = data.username ?? ''
      userEmail.value = data.username ?? '' // username as email fallback
      scopes.value = data.scopes ?? []
      state.value = data.state ?? ''
      csrfToken.value = data.csrf_token ?? getCsrfToken() ?? ''
      if (data.client_id) clientId.value = data.client_id
    }
  } catch {
    // Fall back to query params
    state.value = (route.query.state as string) ?? ''
    const rawScope = (route.query.scope as string) ?? ''
    scopes.value = rawScope ? rawScope.split(' ').filter(Boolean) : []
    csrfToken.value = getCsrfToken() ?? ''
  } finally {
    isLoading.value = false
  }
})
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

    <!-- 7. Action buttons — native form POST to Go (Go handles external redirect) -->
    <form action="/closeauth/oauth2/consent" method="POST" class="space-y-2">
      <!-- Hidden fields for Go handler -->
      <input type="hidden" name="client_id" :value="clientId" />
      <input type="hidden" name="state" :value="state" />
      <input type="hidden" name="csrf_token" :value="csrfToken" />
      <input v-for="s in scopes" :key="s" type="hidden" name="scope" :value="s" />

      <!-- Allow button submits consent=approve -->
      <button
        type="submit"
        name="consent"
        value="approve"
        class="w-full h-10 rounded-md bg-green-600 hover:bg-green-700 text-white text-sm font-semibold active:scale-[0.98] transition-all disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2"
        :disabled="isLoading"
      >
        Allow access
      </button>

      <!-- Deny button submits consent=deny -->
      <button
        type="submit"
        name="consent"
        value="deny"
        class="w-full h-10 rounded-md bg-card border border-border text-foreground text-sm hover:bg-muted transition-colors active:scale-[0.98]"
        :disabled="isLoading"
      >
        Deny
      </button>

      <p class="text-xs text-muted-foreground text-center pt-1">
        By allowing, you agree to share the listed information.
      </p>
    </form>
  </div>
</template>
