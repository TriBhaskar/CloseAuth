import { describe, it, expect } from 'vitest'
import { validateUri, validateUriList } from './uriValidation'

// Client update/delete: a byte-for-byte extraction of CreateClientDialog.vue's
// original inline validateUri — these tests only need to prove the pure
// function, since CreateClientDialog.vue's own spec covers the injection path
// (the wizard form) and TenantClientDetailView.vue's edit form now depends on
// getting the SAME answer from this module.
describe('uriValidation', () => {
  it('accepts a blank value (optional row)', () => {
    expect(validateUri('')).toBeNull()
    expect(validateUri('   ')).toBeNull()
  })

  it('rejects a non-absolute URI', () => {
    expect(validateUri('/relative/path')).toBe('Must be an absolute URI.')
    expect(validateUri('not a url')).toBe('Must be an absolute URI.')
  })

  it('rejects a URI containing a fragment', () => {
    expect(validateUri('https://example.com/callback#token')).toBe('Must not include a fragment.')
  })

  it('requires https unless the host is localhost or 127.0.0.1', () => {
    expect(validateUri('http://example.com/callback')).toBe(
      'Must use https, unless the host is localhost.',
    )
    expect(validateUri('http://localhost:5173/callback')).toBeNull()
    expect(validateUri('http://127.0.0.1/callback')).toBeNull()
    expect(validateUri('https://example.com/callback')).toBeNull()
  })

  it('validateUriList returns only the indices with an error', () => {
    const errors = validateUriList(['https://example.com/callback', 'http://example.com/bad', ''])
    expect(errors).toEqual({ 1: 'Must use https, unless the host is localhost.' })
  })
})
