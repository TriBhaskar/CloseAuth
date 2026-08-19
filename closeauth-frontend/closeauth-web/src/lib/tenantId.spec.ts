import { describe, it, expect } from 'vitest'
import { normalizeTenantId, isValidTenantId } from '@/lib/tenantId'

describe('normalizeTenantId', () => {
  it.each([
    ['already canonical', 'ten_acme-inc', 'ten_acme-inc'],
    ['uppercase', 'TEN_ACME-INC', 'ten_acme-inc'],
    ['missing prefix', 'acme-inc', 'ten_acme-inc'],
    ['surrounding whitespace', '  ten_acme-inc  ', 'ten_acme-inc'],
    ['pasted URL with trailing page', 'https://app.example.com/t/ten_acme-inc/login', 'ten_acme-inc'],
    ['pasted URL with no trailing page', 'https://app.example.com/t/ten_acme-inc', 'ten_acme-inc'],
    ['pasted URL with query string', 'https://app.example.com/t/ten_acme-inc/login?client_id=x', 'ten_acme-inc'],
    ['pasted URL, unprefixed segment', 'https://app.example.com/t/acme-inc', 'ten_acme-inc'],
    ['empty string stays empty', '', ''],
  ])('%s: %s -> %s', (_label, input, expected) => {
    expect(normalizeTenantId(input)).toBe(expected)
  })
})

describe('isValidTenantId', () => {
  it('accepts a canonical ten_-prefixed id', () => {
    expect(isValidTenantId('ten_acme-inc')).toBe(true)
  })

  it.each(['acme-inc', 'ten_', 'ten_A', '', 'ten_bad_char!'])('rejects %s', (value) => {
    expect(isValidTenantId(value)).toBe(false)
  })
})
