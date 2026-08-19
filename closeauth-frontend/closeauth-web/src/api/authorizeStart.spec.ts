import { describe, it, expect, vi, afterEach } from 'vitest'
import { startAuthorize } from '@/api/authorizeStart'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('startAuthorize', () => {
  it('returns an ok result with the authorize URL on a 200', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init: RequestInit) => {
        expect(url).toBe('/api/auth/authorize/start')
        expect(init.method).toBe('POST')
        expect(init.credentials).toBe('include')
        expect(JSON.parse(init.body as string)).toEqual({ tenantId: 'ten_acme-inc' })
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ authorizeUrl: 'https://backend.test/oauth2/authorize?client_id=admin-console-ten_acme-inc' }),
        })
      }),
    )

    const result = await startAuthorize('ten_acme-inc')
    expect(result).toEqual({
      kind: 'ok',
      authorizeUrl: 'https://backend.test/oauth2/authorize?client_id=admin-console-ten_acme-inc',
    })
  })

  it('returns notFound on a 404', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) })))

    const result = await startAuthorize('ten_does-not-exist')
    expect(result).toEqual({ kind: 'notFound' })
  })

  it('returns rateLimited on a 429', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 429, json: () => Promise.resolve(null) })))

    const result = await startAuthorize('ten_acme-inc')
    expect(result).toEqual({ kind: 'rateLimited' })
  })

  it('returns an error result on an unexpected non-ok status', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 502, json: () => Promise.resolve(null) })))

    const result = await startAuthorize('ten_acme-inc')
    expect(result).toEqual({ kind: 'error' })
  })

  it('returns an error result when a 200 body is missing authorizeUrl', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) })))

    const result = await startAuthorize('ten_acme-inc')
    expect(result).toEqual({ kind: 'error' })
  })

  it('returns an error result (never throws) when fetch itself rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await startAuthorize('ten_acme-inc')
    expect(result).toEqual({ kind: 'error' })
  })
})
