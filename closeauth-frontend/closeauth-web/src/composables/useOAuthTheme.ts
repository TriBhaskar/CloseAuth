import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { oauthService } from '@/api/services'

/**
 * Fetches client branding on mount and applies CSS custom properties.
 * Exposes clientId, clientName, and clientLogoUrl as refs.
 *
 * Usage:
 *   const { clientId, clientName, clientLogoUrl } = useOAuthTheme()
 */
export function useOAuthTheme() {
  const route = useRoute()

  const clientId = ref((route.query.client_id as string) ?? '')
  const clientName = ref('the application')
  const clientLogoUrl = ref('')
  const isThemeLoading = ref(false)

  async function loadTheme() {
    if (!clientId.value) return
    isThemeLoading.value = true
    try {
      const theme = await oauthService.fetchTheme(clientId.value)
      if (theme.clientName) clientName.value = theme.clientName
      if (theme.clientLogoUrl) clientLogoUrl.value = theme.clientLogoUrl
      if (theme.themeButton)
        document.documentElement.style.setProperty('--theme-button', theme.themeButton)
      if (theme.themeBackground)
        document.documentElement.style.setProperty('--theme-background', theme.themeBackground)
      if (theme.themeText)
        document.documentElement.style.setProperty('--theme-text', theme.themeText)
    } catch {
      // Silently fall back to defaults already set above
    } finally {
      isThemeLoading.value = false
    }
  }

  return { clientId, clientName, clientLogoUrl, isThemeLoading, loadTheme }
}

