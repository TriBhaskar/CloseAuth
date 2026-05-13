import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export interface ThemeColors {
  primary: string
  background: string
  button: string
  text: string
}

export interface ThemeState {
  clientId: string
  clientName: string
  logoUrl: string
  defaultMode: 'light' | 'dark' | 'system'
  allowModeToggle: boolean
  colors: {
    light: ThemeColors
    dark: ThemeColors
  }
}

const DEFAULT_LIGHT: ThemeColors = { primary: '#3b82f6', background: '#ffffff', button: '#3b82f6', text: '#1f2937' }
const DEFAULT_DARK: ThemeColors = { primary: '#60a5fa', background: '#1f2937', button: '#3b82f6', text: '#f9fafb' }

export const useThemeStore = defineStore('theme', () => {
  const clientId = ref('')
  const clientName = ref('')
  const logoUrl = ref('')
  const defaultMode = ref<'light' | 'dark' | 'system'>('light')
  const allowModeToggle = ref(true)
  const lightColors = ref<ThemeColors>({ ...DEFAULT_LIGHT })
  const darkColors = ref<ThemeColors>({ ...DEFAULT_DARK })
  const currentMode = ref<'light' | 'dark'>('light')

  const activeColors = computed(() =>
    currentMode.value === 'dark' ? darkColors.value : lightColors.value,
  )

  async function loadThemeByClientId(id: string): Promise<void> {
    if (!id) return
    clientId.value = id

    try {
      const resp = await fetch(`/api/oauth/theme?client_id=${encodeURIComponent(id)}`, {
        credentials: 'include',
      })
      if (!resp.ok) return

      const data = await resp.json()
      clientName.value = data.client_name ?? id
      logoUrl.value = data.logo_url ?? ''
      defaultMode.value = data.default_mode ?? 'light'
      allowModeToggle.value = data.allow_mode_toggle ?? true

      if (data.colors?.light) {
        lightColors.value = { ...DEFAULT_LIGHT, ...data.colors.light }
      }
      if (data.colors?.dark) {
        darkColors.value = { ...DEFAULT_DARK, ...data.colors.dark }
      }

      // Set initial mode
      if (defaultMode.value === 'system') {
        currentMode.value = globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
      } else {
        currentMode.value = defaultMode.value === 'dark' ? 'dark' : 'light'
      }

      applyThemeToDOM()
    } catch {
      // Use defaults
    }
  }

  function toggleDarkMode(): void {
    currentMode.value = currentMode.value === 'dark' ? 'light' : 'dark'
    applyThemeToDOM()
  }

  function applyThemeToDOM(): void {
    const colors = activeColors.value
    const root = document.documentElement
    root.style.setProperty('--theme-primary', colors.primary)
    root.style.setProperty('--theme-background', colors.background)
    root.style.setProperty('--theme-button', colors.button)
    root.style.setProperty('--theme-text', colors.text)
  }

  return {
    clientId,
    clientName,
    logoUrl,
    defaultMode,
    allowModeToggle,
    lightColors,
    darkColors,
    currentMode,
    activeColors,
    loadThemeByClientId,
    toggleDarkMode,
    applyThemeToDOM,
  }
})

