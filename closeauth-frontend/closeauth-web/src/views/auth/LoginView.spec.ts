import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './LoginView.vue'

// Stage UI-2a, Deliverable 3's required component test (Vue Test Utils,
// mocked fetch): a successful submission triggers the expected navigation
// target; a failed submission shows the inline error and does NOT navigate.
// Extended for cross-origin login continuity (CLOSEAUTH_CROSS_ORIGIN_LOGIN_
// DESIGN.md §3b / UI-2, frontend prompt): LoginView now captures
// window.location.search directly (not route.query) and forwards it
// verbatim as `authorizeQuery` — so window.location.search itself is what
// these tests set, not the router's query string.
//
// window.location.href/search are stubbed rather than left real — jsdom
// throws "Not implemented: navigation" if a real href assignment is
// attempted, and even if it didn't, actually navigating would tear down the
// test environment. The assertions are on the stub's recorded values, which
// is exactly the behavior under test (LoginView reads window.location.search
// at mount, and sets window.location.href = redirectTo on success).

async function createLoginRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: LoginView },
    ],
  })
  await router.push('/login')
  await router.isReady()
  return router
}

let hrefAssignments: string[]
let locationSearch: string

beforeEach(() => {
  hrefAssignments = []
  locationSearch = ''
  // Branding fetch (useOAuthTheme) — every test gets platform-default
  // branding unless overridden, so it never interferes with the
  // login-submission assertions below.
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              logoUrl: '',
              primaryColor: '#4F46E5',
              backgroundColor: '#FFFFFF',
              accentColor: '#22D3EE',
              companyName: '',
            }),
        })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )

  // jsdom's window.location is read-only for `href` assignment via the real
  // Location object in newer jsdom versions — replace the whole `location`
  // property with a plain recording stub for the duration of each test.
  // `search` is a plain settable string here (not wired to the router's own
  // navigation — vue-router computes route.query from the string handed to
  // router.push, independent of window.location, and LoginView.vue no
  // longer reads route.query at all) so each test sets it directly to
  // whatever query string it wants window.location.search to report.
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      get href() {
        return hrefAssignments.at(-1) ?? ''
      },
      set href(value: string) {
        hrefAssignments.push(value)
      },
      get search() {
        return locationSearch
      },
      set search(value: string) {
        locationSearch = value
      },
    },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, email: string, password: string) {
  await wrapper.find('#login-email').setValue(email)
  await wrapper.find('#login-password').setValue(password)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('LoginView', () => {
  it('navigates to the backend-provided redirectTo on a successful submission', async () => {
    const loginFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'http://backend.test/closeauth/oauth2/authorize?resume=1' }),
    })
    const originalFetch = window.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/auth/login') return loginFetch(url, init)
        return originalFetch(url, init)
      }),
    )

    window.location.search = '?client_id=client-abc'
    const router = await createLoginRouter()
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test', 'correct-password')

    expect(loginFetch).toHaveBeenCalledTimes(1)
    const [, init] = loginFetch.mock.calls[0] as [string, RequestInit]
    const body = JSON.parse(init.body as string)
    expect(body).toMatchObject({
      email: 'user@example.test',
      password: 'correct-password',
      clientId: 'client-abc',
      authorizeQuery: '?client_id=client-abc',
    })
    expect(hrefAssignments).toEqual(['http://backend.test/closeauth/oauth2/authorize?resume=1'])
    // No inline error should render on a successful submission.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('captures the full window.location.search verbatim and forwards it as authorizeQuery, unchanged', async () => {
    // A full, realistic original /oauth2/authorize query string — all seven
    // core OAuth2/PKCE params PLUS an arbitrary OIDC extra (`nonce`) not in
    // that list, to prove LoginView forwards the WHOLE captured string
    // (rather than hand-picking known fields) end-to-end into the JSON
    // payload untouched.
    const realisticQuery =
      '?client_id=client-xyz&redirect_uri=https%3A%2F%2Frp.example.test%2Fcallback' +
      '&response_type=code&scope=openid%20profile&state=st-abc123' +
      '&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256' +
      '&nonce=n-9f3c1a'
    window.location.search = realisticQuery

    const loginFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'http://backend.test/closeauth/oauth2/authorize?resume=1' }),
    })
    const originalFetch = window.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/auth/login') return loginFetch(url, init)
        return originalFetch(url, init)
      }),
    )

    const router = await createLoginRouter()
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test', 'correct-password')

    expect(loginFetch).toHaveBeenCalledTimes(1)
    const [, init] = loginFetch.mock.calls[0] as [string, RequestInit]
    const body = JSON.parse(init.body as string)
    // The captured string is forwarded EXACTLY as window.location.search
    // reported it — not re-encoded, not decomposed, not reordered.
    expect(body.authorizeQuery).toBe(realisticQuery)
    // client_id, derived from the SAME captured string, still matches.
    expect(body.clientId).toBe('client-xyz')
  })

  it('shows an inline error and does NOT navigate on a failed submission', async () => {
    const originalFetch = window.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/auth/login') {
          return Promise.resolve({
            ok: false,
            status: 401,
            json: () => Promise.resolve({ error: 'invalid_credentials', error_description: 'Authentication failed.' }),
          })
        }
        return originalFetch(url, init)
      }),
    )

    const router = await createLoginRouter()
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test', 'wrong-password')

    expect(hrefAssignments).toEqual([])
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text().length).toBeGreaterThan(0)
    // The uniform, enumeration-safe message — never reveals which factor failed.
    expect(alert.text()).not.toContain('invalid_credentials')
  })
})
