import { describe, it, expect } from 'vitest'
import { isSameOriginPath } from './safePath'

describe('isSameOriginPath', () => {
  it('accepts a root-relative path', () => {
    expect(isSameOriginPath('/t/acme/admin/reauth')).toBe(true)
    expect(isSameOriginPath('/platform/console')).toBe(true)
  })

  it('rejects a protocol-relative path (implicit cross-origin)', () => {
    expect(isSameOriginPath('//evil.example.test/steal')).toBe(false)
  })

  it('rejects an absolute URL', () => {
    expect(isSameOriginPath('https://evil.example.test/steal')).toBe(false)
    expect(isSameOriginPath('http://evil.example.test/steal')).toBe(false)
  })

  it('rejects a relative path with no leading slash', () => {
    expect(isSameOriginPath('console')).toBe(false)
    expect(isSameOriginPath('')).toBe(false)
  })
})
