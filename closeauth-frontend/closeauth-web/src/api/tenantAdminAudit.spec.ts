import { describe, it, expect } from 'vitest'
import { resolveRangePreset } from './tenantAdminAudit'

// FE-5.1: the date-range preset resolver backing the audit filter bar's
// 1h/24h/7d/30d/custom presets (spec §6.4.6). `now` is injected so these
// tests aren't tied to wall-clock time.
describe('resolveRangePreset', () => {
  const now = new Date('2026-06-15T12:00:00.000Z')

  it('custom resolves to {} — entirely deferred to the caller-supplied from/to', () => {
    expect(resolveRangePreset('custom', now)).toEqual({})
  })

  it('1h resolves to a from exactly one hour before now, with no to', () => {
    expect(resolveRangePreset('1h', now)).toEqual({ from: '2026-06-15T11:00:00.000Z' })
  })

  it('24h resolves to a from exactly 24 hours before now', () => {
    expect(resolveRangePreset('24h', now)).toEqual({ from: '2026-06-14T12:00:00.000Z' })
  })

  it('7d resolves to a from exactly 7 days before now', () => {
    expect(resolveRangePreset('7d', now)).toEqual({ from: '2026-06-08T12:00:00.000Z' })
  })

  it('30d resolves to a from exactly 30 days before now', () => {
    expect(resolveRangePreset('30d', now)).toEqual({ from: '2026-05-16T12:00:00.000Z' })
  })
})
