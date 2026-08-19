import { describe, it, expect } from 'vitest'
import { previewTenantId } from './tenantIdPreview'

// FE-3b: mirrors TenantSlugGeneratorTest.java's deterministic cases — this
// preview only ports the deterministic half of the real algorithm (see the
// module's own header comment), so the collision-retry/random-suffix cases
// have no analogue here.

describe('previewTenantId', () => {
  it('lowercases and strips diacritics', () => {
    expect(previewTenantId('Acmé Inc.')).toBe('ten_acme-inc')
  })

  it('collapses non-alphanumeric runs to a single hyphen and trims ends', () => {
    expect(previewTenantId('  Foo   Bar!!Baz--')).toBe('ten_foo-bar-baz')
  })

  it('truncates to 40 characters at a hyphen boundary', () => {
    const name = 'aaaaa-bbbbb-ccccc-ddddd-eeeee-fffff-ggggg-hhhhh'
    const result = previewTenantId(name)
    expect(result).toBe('ten_aaaaa-bbbbb-ccccc-ddddd-eeeee-fffff')
    expect(result!.slice('ten_'.length).length).toBeLessThanOrEqual(40)
  })

  it('hard-truncates at 40 when no hyphen exists in range', () => {
    const name = 'a'.repeat(60)
    expect(previewTenantId(name)).toBe('ten_' + 'a'.repeat(40))
  })

  it('returns null (not a fabricated value) when slugification produces nothing', () => {
    // Entirely non-Latin script: no a-z0-9 characters survive.
    expect(previewTenantId('日本語')).toBeNull()
  })

  it('returns null for every reserved word — the real ID would get a random suffix this preview cannot honestly predict', () => {
    for (const reserved of [
      'platform', 'admin', 'api', 'auth', 'oauth2', 'login', 'logout',
      'console', 'account', 't', 'www', 'static', 'health', 'closeauth',
    ]) {
      expect(previewTenantId(reserved)).toBeNull()
    }
  })

  it('returns null for a single-character body regardless of value', () => {
    expect(previewTenantId('Z')).toBeNull()
  })

  it('returns null for an empty or blank name', () => {
    expect(previewTenantId('')).toBeNull()
    expect(previewTenantId('   ')).toBeNull()
  })
})
