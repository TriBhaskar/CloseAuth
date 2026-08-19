import { describe, it, expect, vi, afterEach } from 'vitest'
import { fetchBranding } from '@/api/publicBranding'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchBranding', () => {
  it('returns an ok result with the parsed branding on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              logoUrl: 'https://cdn.acme.test/logo.svg',
              primaryColor: '#123456',
              backgroundColor: '',
              accentColor: '',
              companyName: 'Acme',
            }),
        }),
      ),
    )

    const result = await fetchBranding('admin-console-acme')
    expect(result.kind).toBe('ok')
    if (result.kind !== 'ok') throw new Error('expected ok kind')
    expect(result.value.companyName).toBe('Acme')
  })

  it('returns an error result (never throws) on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 502, json: () => Promise.resolve(null) })))

    const result = await fetchBranding('admin-console-unknown')
    expect(result).toEqual({ kind: 'error' })
  })

  it('returns an error result (never throws) when fetch itself rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await fetchBranding('admin-console-unreachable')
    expect(result).toEqual({ kind: 'error' })
  })

  it('caches by clientId — a second call for the same id does not re-fetch', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({ logoUrl: '', primaryColor: '', backgroundColor: '', accentColor: '', companyName: 'Cached' }),
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await fetchBranding('admin-console-cache-test')
    await fetchBranding('admin-console-cache-test')

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('a failed fetch is NOT cached — a later retry re-fetches', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new Error('down')))
    vi.stubGlobal('fetch', fetchMock)

    await fetchBranding('admin-console-retry-test')
    await fetchBranding('admin-console-retry-test')

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
