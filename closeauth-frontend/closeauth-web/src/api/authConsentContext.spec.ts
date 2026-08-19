import { describe, it, expect, vi, afterEach } from 'vitest'
import { fetchConsentContext } from '@/api/authConsentContext'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchConsentContext', () => {
  it('returns an ok result with the parsed context on success, forwarding the search string verbatim', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            clientId: 'admin-console-acme',
            clientName: 'Acme Console',
            state: 'xyz',
            scopes: [],
            alreadyGranted: [],
            authorizeUrl: 'https://backend.test/oauth2/authorize',
          }),
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchConsentContext('?client_id=admin-console-acme&scope=openid&state=xyz')
    expect(result.kind).toBe('ok')
    if (result.kind !== 'ok') throw new Error('expected ok kind')
    expect(result.value.clientName).toBe('Acme Console')
    expect(fetchMock).toHaveBeenCalledWith(
      '/oauth2/consent?client_id=admin-console-acme&scope=openid&state=xyz',
      { credentials: 'omit' },
    )
  })

  it('returns an error result (never throws) on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 400, json: () => Promise.resolve(null) })))

    const result = await fetchConsentContext('?client_id=bad')
    expect(result).toEqual({ kind: 'error' })
  })

  it('returns an error result (never throws) when fetch itself rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await fetchConsentContext('?client_id=acme')
    expect(result).toEqual({ kind: 'error' })
  })
})
