// FE-5.3: extracted out of components/common/TenantBrandingProvider.vue
// (FE-1.3), which owned this validation because it's the actual security
// boundary — attacker-controlled tenant branding reaching a CSS custom
// property. FE-5.3's live login-card preview (BrandingLoginPreview.vue) and
// the branding settings form need the SAME validation and contrast rules the
// hosted pages apply, not a second, drifting copy — so the pure functions
// move here and both the provider and the preview import from this one file.
//
// Nothing behavioural changes: every function below is a byte-for-byte move,
// not a rewrite.

// §3.2 rule 3: validate every branding value client-side before injection,
// even though the backend validates on write. Tenant branding is attacker-
// controlled input rendered into CSS/markup — treat it as such.
const HEX_COLOR = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/

export function isValidHexColor(value: string): boolean {
  return HEX_COLOR.test(value)
}

export function isValidLogoUrl(value: string): boolean {
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

// WCAG relative-luminance contrast, sRGB hex only (branding colours are
// always #RRGGBB/#RGB by the validation above).
export function hexToRgb(hex: string): [number, number, number] {
  const full = hex.length === 4 ? `#${hex.slice(1).replace(/./g, (c) => c + c)}` : hex
  const int = Number.parseInt(full.slice(1), 16)
  return [(int >> 16) & 255, (int >> 8) & 255, int & 255]
}

export function relativeLuminance([r, g, b]: [number, number, number]): number {
  const [rs, gs, bs] = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
}

export function contrastRatio(hex: string, otherLuminance: number): number {
  const luminance = relativeLuminance(hexToRgb(hex))
  const lighter = Math.max(luminance, otherLuminance)
  const darker = Math.min(luminance, otherLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

// The well-known sRGB hex equivalents of tokens.css's --ca-canvas literals
// (zinc-50 / zinc-950) — kept as constants rather than reading computed
// styles from the DOM, which would add a paint-timing dependency for no
// benefit: these two values are the source of truth already.
export const CANVAS_LUMINANCE = {
  light: relativeLuminance(hexToRgb('#fafafa')), // zinc-50
  dark: relativeLuminance(hexToRgb('#09090b')), // zinc-950
} as const

export const MIN_CONTRAST = 4.5

/** True when `hex` meets §8's 4.5:1 text-contrast floor against the given mode's canvas. */
export function meetsContrast(hex: string, mode: 'light' | 'dark'): boolean {
  return (
    contrastRatio(hex, mode === 'dark' ? CANVAS_LUMINANCE.dark : CANVAS_LUMINANCE.light) >=
    MIN_CONTRAST
  )
}

/** §3.2 rule 4's fallback: the raw brand colour if it's safe for text on the current mode's canvas, else --ca-accent. */
export function brandTextColor(hex: string, mode: 'light' | 'dark'): string {
  return meetsContrast(hex, mode) ? hex : 'var(--ca-accent)'
}
