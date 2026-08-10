import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchSession, signOut as apiSignOut, type PlatformAdminSessionState } from '@/api/platformAdminSession'

// Stage UI-4: the platform-admin console's session store. Own Pinia store,
// never merged with stores/tenantAdmin.ts or stores/auth.ts — both carry
// explicit standing prohibitions against conflating these principal types
// (platform admins are a separate entity, not a role on a tenant user).
// Honors stores/admin.ts's standing rule verbatim: `state` defaults to an
// explicit 'anonymous', never a fabricated 'active'.
export const usePlatformAdminSessionStore = defineStore('platformAdminSession', () => {
  const state = ref<PlatformAdminSessionState>({ kind: 'anonymous' })
  const isLoading = ref(false)

  async function load(): Promise<PlatformAdminSessionState> {
    isLoading.value = true
    try {
      const result = await fetchSession()
      state.value = result
      return result
    } finally {
      isLoading.value = false
    }
  }

  async function signOut(): Promise<void> {
    await apiSignOut()
    state.value = { kind: 'anonymous' }
  }

  return { state, isLoading, load, signOut }
})
