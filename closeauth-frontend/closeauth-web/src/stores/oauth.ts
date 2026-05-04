import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * OAuth context store — holds client-side display state for OAuth flow pages.
 * The actual OAuth context (tokens, JSESSIONID) lives server-side in encrypted cookies.
 * This store only manages UI display state fetched from GET /api/oauth/consent-data.
 */
export const useOAuthStore = defineStore('oauth', () => {
  const clientId = ref('')
  const clientName = ref('')
  const logoUrl = ref('')
  const username = ref('')
  const scopes = ref<string[]>([])
  const state = ref('')
  const csrfToken = ref('')

  async function loadConsentData(): Promise<void> {
    try {
      const resp = await fetch('/api/oauth/consent-data', { credentials: 'include' })
      if (!resp.ok) return

      const data = await resp.json()
      clientId.value = data.client_id ?? ''
      clientName.value = data.client_name ?? ''
      logoUrl.value = data.logo_url ?? ''
      username.value = data.username ?? ''
      scopes.value = data.scopes ?? []
      state.value = data.state ?? ''
      csrfToken.value = data.csrf_token ?? ''
    } catch {
      // Silently fail
    }
  }

  function clear(): void {
    clientId.value = ''
    clientName.value = ''
    logoUrl.value = ''
    username.value = ''
    scopes.value = []
    state.value = ''
    csrfToken.value = ''
  }

  return { clientId, clientName, logoUrl, username, scopes, state, csrfToken, loadConsentData, clear }
})

