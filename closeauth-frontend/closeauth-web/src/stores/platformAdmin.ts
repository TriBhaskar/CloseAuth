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

  // FE-3a (spec §6.3.1): drives the in-place re-authentication overlay in
  // layouts/PlatformAdminLayout.vue. 'proactive' = the operator clicked
  // "Stay signed in" at T-60s (session still valid, dismissible — see
  // requestReauth). 'expired' = the countdown reached 0:00, or a request
  // came back session-expired early (see platformAdminClient.ts) — no valid
  // session left, not dismissible.
  const needsReauth = ref<'proactive' | 'expired' | null>(null)

  function requestReauth(mode: 'proactive' | 'expired'): void {
    // 'expired' always wins — a genuinely dead session is never downgraded
    // back to the dismissible proactive prompt by a late-arriving proactive
    // call (e.g. the topbar's own click racing the ticker's own trigger).
    if (needsReauth.value === 'expired') return
    needsReauth.value = mode
  }

  function completeReauth(newState: PlatformAdminSessionState): void {
    state.value = newState
    needsReauth.value = null
  }

  /** Only meaningful for the dismissible 'proactive' prompt — the operator chose to keep working on the existing session. */
  function dismissReauth(): void {
    needsReauth.value = null
  }

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
    needsReauth.value = null
  }

  return { state, isLoading, needsReauth, requestReauth, completeReauth, dismissReauth, load, signOut }
})
