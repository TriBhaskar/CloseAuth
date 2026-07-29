import { defineStore } from 'pinia'

// TODO(ui-2): rebuild this store as part of the hosted, tenant-branded
// end-user auth pages (login, all 4 registration modes, verify, magic-link,
// reset, consent — vision §8.7). The deleted version fetched consent-display
// state from a stale `/api/oauth/consent-data` endpoint; that contract no
// longer exists and needs to be rebuilt against the current backend.
export const useOAuthStore = defineStore('oauth', () => {
  return {}
})
