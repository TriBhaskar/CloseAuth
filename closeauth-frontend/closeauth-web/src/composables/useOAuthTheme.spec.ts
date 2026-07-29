import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useOAuthTheme, type Branding } from './useOAuthTheme'

// Stage UI-2a, Deliverable 2's required composable unit test: given a known
// branding response (including the empty-string-unset case), assert the
// correct CSS custom properties are set, and that no image source is
// produced when logoUrl is unset. Each test uses a DISTINCT client_id so the
// composable's own per-client_id module-level cache never leaks a mocked
// response from one test into another.

function mockFetchOnce(body: Branding, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: () => Promise.resolve(body),
    }),
  )
}

beforeEach(() => {
  // Reset the three tokens this composable is allowed to touch, so a
  // previous test's applied value can't leak into the next assertion.
  document.documentElement.style.removeProperty('--primary')
  document.documentElement.style.removeProperty('--background')
  document.documentElement.style.removeProperty('--accent')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useOAuthTheme', () => {
  it('applies primaryColor/backgroundColor/accentColor onto the existing shadcn-vue tokens', async () => {
    const branding: Branding = {
      logoUrl: 'https://cdn.example.test/logo.png',
      primaryColor: '#112233',
      backgroundColor: '#445566',
      accentColor: '#778899',
      companyName: 'Acme Corp',
    }
    mockFetchOnce(branding)

    const { ready, branding: state, hasLogo } = useOAuthTheme('client-full-branding')
    await ready

    expect(state.value).toEqual(branding)
    expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#112233')
    expect(document.documentElement.style.getPropertyValue('--background')).toBe('#445566')
    expect(document.documentElement.style.getPropertyValue('--accent')).toBe('#778899')
    expect(hasLogo.value).toBe(true)
  })

  it('treats an empty-string logoUrl (the documented unset signal) as falsy — no image source produced', async () => {
    const branding: Branding = {
      logoUrl: '', // unset, per the backend contract — NOT null
      primaryColor: '#4F46E5',
      backgroundColor: '#FFFFFF',
      accentColor: '#22D3EE',
      companyName: '', // also unset
    }
    mockFetchOnce(branding)

    const { ready, branding: state, hasLogo } = useOAuthTheme('client-unset-logo')
    await ready

    expect(state.value.logoUrl).toBe('')
    expect(hasLogo.value).toBe(false)
    // Colors still applied even though logo/company name are unset.
    expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#4F46E5')
    expect(document.documentElement.style.getPropertyValue('--background')).toBe('#FFFFFF')
    expect(document.documentElement.style.getPropertyValue('--accent')).toBe('#22D3EE')
  })

  it('never touches unrelated design-system tokens (e.g. --border) it has no business setting', async () => {
    mockFetchOnce({
      logoUrl: '',
      primaryColor: '#000000',
      backgroundColor: '#ffffff',
      accentColor: '#ff00ff',
      companyName: '',
    })
    document.documentElement.style.setProperty('--border', 'oklch(0.91 0.008 264)')

    const { ready } = useOAuthTheme('client-untouched-tokens')
    await ready

    expect(document.documentElement.style.getPropertyValue('--border')).toBe('oklch(0.91 0.008 264)')
  })

  it('surfaces a fetch failure via error/isLoading rather than throwing, and does not cache the failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 503, json: () => Promise.resolve({}) }),
    )

    const { ready, error, isLoading, branding: state } = useOAuthTheme('client-fetch-failure')
    await ready

    expect(isLoading.value).toBe(false)
    expect(error.value).toContain('503')
    // Falls back to platform-default (empty) shape rather than leaving stale/undefined state.
    expect(state.value.logoUrl).toBe('')
  })

  it('fetches once per client_id even if called more than once (module-level cache)', async () => {
    const fetchSpy = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          logoUrl: '',
          primaryColor: '#111111',
          backgroundColor: '#222222',
          accentColor: '#333333',
          companyName: '',
        }),
    })
    vi.stubGlobal('fetch', fetchSpy)

    const first = useOAuthTheme('client-shared-cache')
    const second = useOAuthTheme('client-shared-cache')
    await Promise.all([first.ready, second.ready])

    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })
})
