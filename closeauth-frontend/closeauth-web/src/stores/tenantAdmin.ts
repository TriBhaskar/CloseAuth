import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchSession, type TenantAdminSessionState } from '@/api/tenantAdminSession'

// Stage UI-3a: the tenant-admin console's session store. Honors
// stores/admin.ts's standing prohibition verbatim (that store is UI-3b's
// CRUD dashboard, left untouched here): a view that can't get real data
// shows a visible error/loading state, never falls back to fake data — so
// `state` defaults to an explicit 'anonymous', never a fabricated 'active'.
//
// Deliberately its own store, not layered onto stores/auth.ts — that
// store's own TODO forbids conflating tenant-admin and platform-admin (or,
// here, tenant-admin and the public-page placeholder) auth state under one
// shape.
export const useTenantAdminSessionStore = defineStore('tenantAdminSession', () => {
  const slug = ref('')
  const state = ref<TenantAdminSessionState>({ kind: 'anonymous' })
  const isLoading = ref(false)

  async function load(forSlug: string): Promise<TenantAdminSessionState> {
    isLoading.value = true
    slug.value = forSlug
    try {
      const result = await fetchSession(forSlug)
      state.value = result
      return result
    } finally {
      isLoading.value = false
    }
  }

  // A real top-level navigation, never fetch() — CLOSEAUTH_SESSION is
  // SameSite=Lax, so only a genuine browser navigation to the BFF's
  // GET /t/{slug}/admin/logout can end the backend session too (that
  // handler clears the BFF's own cookies AND continues on through the
  // backend's own /logout cascade). This is why signOut() doesn't return a
  // result the caller can branch on — the page is already navigating away.
  function signOut(): void {
    if (!slug.value) return
    state.value = { kind: 'anonymous' }
    window.location.assign(`/t/${slug.value}/admin/logout`)
  }

  return { slug, state, isLoading, load, signOut }
})
