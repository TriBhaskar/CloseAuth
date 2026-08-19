<script setup lang="ts">
// FE-1.3 (spec §3.2, §3.8): replaces composables/useOAuthTheme.ts entirely.
// The old composable wrote --primary/--background/--accent onto
// document.documentElement, global to the whole document; a tenant's brand
// colour could leak into a console screen mounted later in the same
// session. This component scopes injection to its own root element
// (data-scope="hosted"), which is exactly what §3.2 rule 2 requires and
// what made this component necessary rather than a one-line patch to the
// composable.
//
// FE-1.11: the fetch/cache itself moved to api/publicBranding.ts (spec §9
// rule 2 — no component-level fetch survives). This component keeps the
// validation logic below, since that's the actual security boundary (what
// reaches a CSS custom property), not the transport.
import { computed, ref, useTemplateRef, watchEffect } from 'vue'
import { fetchBranding, type Branding } from '@/api/publicBranding'
import { hasBrandingLogo } from '@/lib/branding'
import { useThemeStore } from '@/stores/theme'

export type { Branding }

const props = defineProps<{ clientId: string }>()

const PLATFORM_DEFAULT: Branding = {
  logoUrl: '',
  primaryColor: '',
  backgroundColor: '',
  accentColor: '',
  companyName: '',
  tenantSlug: null,
  registrationMode: null,
}

// §3.2 rule 3: validate every branding value client-side before injection,
// even though the backend validates on write. Tenant branding is attacker-
// controlled input rendered into CSS/markup — treat it as such. Anything
// invalid falls back to the CloseAuth default SILENTLY (no error surfaced
// to the tenant's end user over a cosmetic default).
const HEX_COLOR = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/

function isValidHexColor(value: string): boolean {
  return HEX_COLOR.test(value)
}

function isValidLogoUrl(value: string): boolean {
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

// WCAG relative-luminance contrast, sRGB hex only (branding colours are
// always #RRGGBB/#RGB by the validation above). Canvas constants are the
// well-known sRGB hex equivalents of tokens.css's --ca-canvas literals
// (zinc-50 / zinc-950) — kept as constants rather than reading computed
// styles from the DOM, which would add a paint-timing dependency for no
// benefit: these two values are the source of truth already.
function hexToRgb(hex: string): [number, number, number] {
  const full = hex.length === 4 ? `#${hex.slice(1).replace(/./g, (c) => c + c)}` : hex
  const int = Number.parseInt(full.slice(1), 16)
  return [(int >> 16) & 255, (int >> 8) & 255, int & 255]
}

function relativeLuminance([r, g, b]: [number, number, number]): number {
  const [rs, gs, bs] = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
}

function contrastRatio(hex: string, otherLuminance: number): number {
  const luminance = relativeLuminance(hexToRgb(hex))
  const lighter = Math.max(luminance, otherLuminance)
  const darker = Math.min(luminance, otherLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

const CANVAS_LUMINANCE = {
  light: relativeLuminance(hexToRgb('#fafafa')), // zinc-50
  dark: relativeLuminance(hexToRgb('#09090b')), // zinc-950
} as const

const MIN_CONTRAST = 4.5

const themeStore = useThemeStore()
const rootRef = useTemplateRef<HTMLElement>('root')

const branding = ref<Branding>({ ...PLATFORM_DEFAULT })
const isLoading = ref(true)
const error = ref('')

// Mandatory truthiness + safety guard: unset logoUrl is "", never null (the
// backend contract useOAuthTheme.ts documented), AND an invalid/unsafe URL
// (non-https, javascript:, malformed) must never reach a consumer's <img
// :src> binding — falls back to "no logo" exactly like an unset one.
const hasLogo = computed(
  () => hasBrandingLogo(branding.value.logoUrl) && isValidLogoUrl(branding.value.logoUrl),
)

async function load(): Promise<void> {
  isLoading.value = true
  error.value = ''
  const result = await fetchBranding(props.clientId)
  if (result.kind === 'ok') {
    branding.value = result.value
  } else {
    error.value = 'Failed to load branding'
  }
  isLoading.value = false
}

void load()

// §3.8 "Theme × tenant branding": the contrast check runs per theme, not
// once — re-evaluated whenever the resolved theme changes, not just at
// load. --brand-primary is the raw brand colour (non-text accents, e.g. a
// button fill); --brand-primary-text substitutes --ca-accent when the raw
// colour fails contrast against the CURRENT mode's canvas. Every consumer
// follows §3.2 rule 4: var(--brand-primary, var(--ca-accent)).
watchEffect(() => {
  const root = rootRef.value
  if (!root) return

  const b = branding.value
  if (b.primaryColor && isValidHexColor(b.primaryColor)) {
    root.style.setProperty('--brand-primary', b.primaryColor)
    const canvasLuminance = themeStore.resolved === 'dark' ? CANVAS_LUMINANCE.dark : CANVAS_LUMINANCE.light
    const safe = contrastRatio(b.primaryColor, canvasLuminance) >= MIN_CONTRAST
    root.style.setProperty('--brand-primary-text', safe ? b.primaryColor : 'var(--ca-accent)')
  } else {
    root.style.removeProperty('--brand-primary')
    root.style.removeProperty('--brand-primary-text')
  }

  if (b.backgroundColor && isValidHexColor(b.backgroundColor)) {
    root.style.setProperty('--brand-background', b.backgroundColor)
  } else {
    root.style.removeProperty('--brand-background')
  }

  if (b.accentColor && isValidHexColor(b.accentColor)) {
    root.style.setProperty('--brand-accent', b.accentColor)
  } else {
    root.style.removeProperty('--brand-accent')
  }
})
</script>

<template>
  <div ref="root" data-scope="hosted" class="contents">
    <slot :branding="branding" :has-logo="hasLogo" :is-loading="isLoading" :error="error" />
  </div>
</template>
