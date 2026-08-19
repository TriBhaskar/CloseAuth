import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'closeauth.theme'
const MODES: readonly ThemeMode[] = ['light', 'dark', 'system']

function readStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system'
  } catch {
    return 'system'
  }
}

// FE-1.1b (spec §3.8): the ONE mechanism. `mode` is the user's stated
// preference (persisted); `resolved` is the actual light/dark value —
// `system` is not a paint-time value, it's a live subscription to
// matchMedia('(prefers-color-scheme: dark)'). The store's only structural
// job is one effect: write `resolved` to document.documentElement.dataset
// .theme. Nothing else in the app should ever touch that attribute or add a
// .dark class — see base.css's header comment for why `dark:` is left
// undefined rather than redirected here.
//
// The inline pre-paint script in index.html duplicates this store's
// resolution logic (mode → resolved) so the attribute is correct before Vue
// even mounts — see that script's own comment. This store takes over from
// there: it re-reads the same localStorage key so its initial state matches
// what was already painted, and it's what keeps the attribute live
// afterwards (OS theme flips while `system` is active, the topbar toggle).
export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStoredMode())

  const prefersDark = ref(
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false,
  )

  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (e: MediaQueryListEvent) => {
      prefersDark.value = e.matches
    }
    if (typeof media.addEventListener === 'function') {
      media.addEventListener('change', onChange)
    }
  }

  const resolved = computed<ResolvedTheme>(() => {
    if (mode.value === 'system') return prefersDark.value ? 'dark' : 'light'
    return mode.value
  })

  watch(
    resolved,
    (value) => {
      document.documentElement.dataset.theme = value
    },
    { immediate: true },
  )

  watch(mode, (value) => {
    try {
      localStorage.setItem(STORAGE_KEY, value)
    } catch {
      // Storage unavailable (private browsing, quota) — the resolved theme
      // still applies for this session, it just won't survive a reload.
    }
  })

  function setMode(next: ThemeMode): void {
    mode.value = next
  }

  function cycle(): void {
    const i = MODES.indexOf(mode.value)
    mode.value = MODES[(i + 1) % MODES.length]!
  }

  return { mode, resolved, setMode, cycle }
})
