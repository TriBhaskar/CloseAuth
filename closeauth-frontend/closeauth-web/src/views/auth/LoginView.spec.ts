import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './LoginView.vue'

// Stage UI-2a, Deliverable 3's required component test (Vue Test Utils,
// mocked fetch): a successful submission triggers the expected navigation
// target; a failed submission shows the inline error and does NOT navigate.
//
// window.location.href is stubbed rather than left real — jsdom throws
// "Not implemented: navigation" if a real assignment is attempted, and even
// if it didn't, actually navigating would tear down the test environment.
// The assertion is on the stub's recorded value, which is exactly the
// behavior under test (LoginView sets window.location.href = redirectTo).

async function createLoginRouter(clientId = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: LoginView },
    ],
  })
  await router.push(clientId ? `/login?client_id=${clientId}` : '/login')
  await router.isReady()
  return router
}

let hrefAssignments: string[]

beforeEach(() => {
  hrefAssignments = []
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
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      get href() {
        return hrefAssignments.at(-1) ?? ''
      },
      set href(value: string) {
        hrefAssignments.push(value)
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

    const router = await createLoginRouter('client-abc')
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
    })
    expect(hrefAssignments).toEqual(['http://backend.test/closeauth/oauth2/authorize?resume=1'])
    // No inline error should render on a successful submission.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
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
