import { defineStore } from 'pinia'
import { ref } from 'vue'

// TODO(ui-1/ui-2): rebuild this store against the current backend's principal
// model. The deleted version modeled a single flat "admin" user (email,
// username, role) fetched from a stale endpoint and persisted to
// localStorage — the current backend has two genuinely separate principal
// types, TENANT_ADMIN and PLATFORM_ADMIN (vision §7.8), plus tenant end-users.
// This store needs to distinguish tenant-user auth state from platform-admin
// auth state rather than conflating them the way the old code conflated
// "client" and "tenant".
//
// The `email`/`isAuthenticated` state and the `logout` stub below are kept as
// placeholders only because src/views/public/HomeView.vue (public landing
// page, out of scope for this whole effort) depends on this store's shape at
// compile time. (Its other UI-0 dependent, src/layouts/AdminLayout.vue, is
// gone — UI-4 replaced it with layouts/PlatformAdminLayout.vue +
// stores/platformAdmin.ts, a real session store against the actual
// platform-admin principal, not this placeholder.) None of this is real auth
// logic — no persistence, no API calls, no session handling.
export const useAuthStore = defineStore('auth', () => {
  const email = ref('')
  const isAuthenticated = ref(false)

  function logout(): void {
    // TODO(ui-1/ui-2): real logout (clear session, call backend, redirect).
  }

  return { email, isAuthenticated, logout }
})
