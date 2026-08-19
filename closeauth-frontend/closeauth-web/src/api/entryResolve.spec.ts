import { describe, it, expect, vi, afterEach } from 'vitest'
import { resolveTenant } from '@/api/entryResolve'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('resolveTenant', () => {
  it('returns an ok result with the parsed resolution on a 200', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        expect(url).toBe('/api/entry/resolve?tenantId=ten_acme-inc')
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
        })
      }),
    )

    const result = await resolveTenant('ten_acme-inc')
    expect(result).toEqual({
      kind: 'ok',
      value: { tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' },
    })
  })

  it('returns notFound on a 404 — unknown, suspended, deleted, and rate-limited are byte-identical', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) })))

    const result = await resolveTenant('ten_does-not-exist')
    expect(result).toEqual({ kind: 'notFound' })
  })

  it('returns an error result (never throws) on an unexpected non-404 error status', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 502, json: () => Promise.resolve(null) })))

    const result = await resolveTenant('ten_acme-inc')
    expect(result).toEqual({ kind: 'error' })
  })

  it('returns an error result (never throws) when fetch itself rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await resolveTenant('ten_acme-inc')
    expect(result).toEqual({ kind: 'error' })
  })

  it('URL-encodes the tenantId in the query string', async () => {
    const fetchMock = vi.fn(() => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) }))
    vi.stubGlobal('fetch', fetchMock)

    await resolveTenant('ten_a b&c')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/entry/resolve?tenantId=ten_a%20b%26c',
      expect.objectContaining({ credentials: 'omit' }),
    )
  })
})
