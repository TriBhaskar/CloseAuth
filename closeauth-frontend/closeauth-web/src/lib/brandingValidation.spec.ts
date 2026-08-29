import { describe, it, expect } from 'vitest'
import {
  isValidHexColor,
  isValidLogoUrl,
  meetsContrast,
  brandTextColor,
} from './brandingValidation'

// FE-5.3: this module is a byte-for-byte extraction of
// TenantBrandingProvider.vue's validation/contrast logic (see that
// component's own spec for the injection-path coverage) — these tests only
// need to prove the pure functions themselves, since BrandingLoginPreview.vue
// and the provider both now depend on getting the SAME answer from them.
describe('brandingValidation', () => {
  it('accepts 3- and 6-digit hex colours, rejects everything else', () => {
    expect(isValidHexColor('#fff')).toBe(true)
    expect(isValidHexColor('#4F46E5')).toBe(true)
    expect(isValidHexColor('red')).toBe(false)
    expect(isValidHexColor('red;}body{display:none')).toBe(false)
    expect(isValidHexColor('')).toBe(false)
  })

  it('accepts only https URLs as a logo URL', () => {
    expect(isValidLogoUrl('https://cdn.example.test/logo.png')).toBe(true)
    expect(isValidLogoUrl('http://insecure.example.test/logo.png')).toBe(false)
    expect(isValidLogoUrl('javascript:alert(1)')).toBe(false)
    expect(isValidLogoUrl('not a url')).toBe(false)
  })

  it('meetsContrast is false for a near-white colour against the light canvas', () => {
    expect(meetsContrast('#fefefe', 'light')).toBe(false)
  })

  it('meetsContrast is true for near-black against the light canvas', () => {
    expect(meetsContrast('#000000', 'light')).toBe(true)
  })

  it('brandTextColor falls back to --ca-accent only when contrast fails', () => {
    expect(brandTextColor('#fefefe', 'light')).toBe('var(--ca-accent)')
    expect(brandTextColor('#000000', 'light')).toBe('#000000')
  })
})
