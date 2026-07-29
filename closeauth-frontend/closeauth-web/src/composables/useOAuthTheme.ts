import { computed, ref } from 'vue'

// Stage UI-2a, Deliverable 2: the ONE shared branding composable for every
// hosted end-user auth page (login now; registration/verify/magic-link/
// reset/consent in UI-2b/2c reuse this same composable — they must NOT grow
// their own divergent copies, which is exactly the bug the original codebase
// snapshot flagged).
//
// Contract with the backend (GET /branding?client_id=..., API_REFERENCE.md
// §1, public/unauthenticated): 200 {logoUrl, primaryColor, backgroundColor,
// accentColor, companyName}. Unset logoUrl/companyName come back as an
// EMPTY STRING, not null — this is load-bearing: an ungated `<img src="">`
// makes the browser re-request the current page. `hasLogo` below is the one
// place that truthiness guard lives; every consumer must gate on it (or the
// equivalent `branding.logoUrl` truthiness) before ever binding `<img>`.
//
// Design decision — no Pinia store: src/stores/theme.ts is left as the
// UI-0 TODO shell it already was. The previous (deleted) theme store was
// "confirmed-dead code" per that file's own comment, and branding here is
// strictly page-scoped (keyed by client_id, read once at page load, never
// mutated by user interaction) — there's no cross-component/cross-route
// state to coordinate that would justify a store. If a later stage needs
// branding available app-wide (e.g. a tenant-admin preview), that's a new
// requirement to design for then, not a reason to add one now.
export interface Branding {
  logoUrl: string
  primaryColor: string
  backgroundColor: string
  accentColor: string
  companyName: string
}

// CSS custom property targets: the EXISTING shadcn-vue tokens already
// defined (light + dark) in src/assets/main.css, feeding Tailwind via that
// file's `@theme inline` block. Deliberately not inventing new variable
// names, and deliberately only these three — chart colors, sidebar colors,
// etc. are CloseAuth's own fixed design system, not tenant-brandable.
const CSS_VAR_PRIMARY = '--primary'
const CSS_VAR_BACKGROUND = '--background'
const CSS_VAR_ACCENT = '--accent'

// The `<!-- CLOSEAUTH_THEME_INJECT -->` marker in index.html is a landmark
// from before this rebuild, not something a client-rendered SPA can bind a
// dynamic style insertion to at runtime (it's a static HTML comment, gone by
// the time Vue mounts — there's no live DOM node at that position to target).
// Setting the custom properties directly on document.documentElement via
// style.setProperty achieves the identical effect: an inline style declared
// on :root has higher specificity than main.css's `:root`/`.dark` rules, so
// it overrides them for exactly the three tenant-brandable tokens, leaving
// every other token (and dark-mode's own overrides of THESE tokens, since we
// overwrite unconditionally) as main.css defines them until a fetch resolves.
function applyBrandingTokens(branding: Branding): void {
  const root = document.documentElement
  if (branding.primaryColor) root.style.setProperty(CSS_VAR_PRIMARY, branding.primaryColor)
  if (branding.backgroundColor) root.style.setProperty(CSS_VAR_BACKGROUND, branding.backgroundColor)
  if (branding.accentColor) root.style.setProperty(CSS_VAR_ACCENT, branding.accentColor)
}

const PLATFORM_DEFAULT: Branding = {
  logoUrl: '',
  primaryColor: '',
  backgroundColor: '',
  accentColor: '',
  companyName: '',
}

// Module-level cache, keyed by client_id: "fetch once per page-load" means
// once per (client_id, page load) — not once per component instance. Two
// components mounting on the same page for the same client_id (e.g. a header
// logo + the login form) must share one in-flight fetch, not race two, and
// remounting (e.g. a hot-reload-free client-side nav back to the same login
// page in the same tab) must not refetch. Cleared on a genuine fetch failure
// so a later retry (e.g. the user reloading) isn't cached as a permanent dud.
const cache = new Map<string, Promise<Branding>>()

async function fetchBranding(clientId: string): Promise<Branding> {
  const query = clientId ? `?client_id=${encodeURIComponent(clientId)}` : ''
  const response = await fetch(`/branding${query}`, { credentials: 'omit' })
  if (!response.ok) {
    throw new Error(`branding fetch failed: HTTP ${response.status}`)
  }
  return (await response.json()) as Branding
}

/**
 * Resolves and applies tenant branding for clientId. Call once per hosted
 * auth page (e.g. in LoginView's setup()); every field is reactive so the
 * template updates once the fetch resolves.
 */
export function useOAuthTheme(clientId: string) {
  const branding = ref<Branding>({ ...PLATFORM_DEFAULT })
  const isLoading = ref(true)
  const error = ref('')

  // Mandatory truthiness guard: unset logoUrl is "", never null — `hasLogo`
  // is the ONE place this composable's consumers should check before binding
  // <img>, e.g. `v-if="hasLogo"` alongside `:src="branding.logoUrl"`.
  const hasLogo = computed(() => branding.value.logoUrl !== '')

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = ''
    try {
      let pending = cache.get(clientId)
      if (!pending) {
        pending = fetchBranding(clientId)
        cache.set(clientId, pending)
      }
      const resolved = await pending
      branding.value = resolved
      applyBrandingTokens(resolved)
    } catch (err) {
      cache.delete(clientId)
      error.value = err instanceof Error ? err.message : 'Failed to load branding'
    } finally {
      isLoading.value = false
    }
  }

  const ready = load()

  return { branding, isLoading, error, hasLogo, ready }
}
