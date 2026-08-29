import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformLoginView from './PlatformLoginView.vue'

// Stage UI-4: proves the login form's three real outcomes — success pushes
// to returnTo (client-side, never window.location.assign: there is no
// backend SSO entry point to reach here), invalid_credentials renders a
// uniform enumeration-safe message, and not_platform_admin renders its own
// specific copy (the "creation alone doesn't grant access" case) rather than
// being flattened into a generic error.

async function createLoginRouter(initialPath = '/platform/login') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/platform/login', component: PlatformLoginView },
      { path: '/platform/console', component: { template: '<div />' } },
      { path: '/platform/console/tenants', component: { template: '<div />' } },
    ],
  })
  await router.push(initialPath)
  await router.isReady()
  return router
}

beforeEach(() => {
  // GET /api/csrf — every mutating platformAdminFetch call fetches this
  // first (fetchCsrfToken, shared with the tenant-admin console).
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/api/csrf') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ token: 'csrf-token' }),
        })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, email: string, password: string) {
  await wrapper.find('#platform-login-email').setValue(email)
  await wrapper.find('#platform-login-password').setValue(password)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

function stubLoginFetch(
  handler: (init: RequestInit) => { ok: boolean; status: number; body: unknown },
) {
  const originalFetch = window.fetch
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url === '/platform/api/login') {
        const { ok, status, body } = handler(init ?? {})
        return Promise.resolve({
          ok,
          status,
          json: () => Promise.resolve(body),
          clone() {
            return this
          },
        })
      }
      return originalFetch(url, init)
    }),
  )
}

describe('PlatformLoginView', () => {
  it('FE-3a: spec-literal "Platform sign in" title and a "back to workspace sign-in" link to /', async () => {
    const router = await createLoginRouter()
    const wrapper = mount(PlatformLoginView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Platform sign in')
    const backLink = wrapper
      .findAll('a')
      .find((a) => a.text().includes('Back to workspace sign-in'))
    expect(backLink?.attributes('href')).toBe('/')
  })

  it('successful login pushes to returnTo, client-side (no window.location.assign)', async () => {
    stubLoginFetch(() => ({
      ok: true,
      status: 200,
      body: {
        authenticated: true,
        adminId: 'admin-1',
        email: 'staff@closeauth.test',
        roles: ['PLATFORM_ADMIN'],
        accessTokenExpiresAt: '2026-01-01T00:05:00Z',
      },
    }))
    const assignSpy = vi.fn()
    Object.defineProperty(window, 'location', { configurable: true, value: { assign: assignSpy } })

    const router = await createLoginRouter(
      '/platform/login?returnTo=%2Fplatform%2Fconsole%2Ftenants',
    )
    const wrapper = mount(PlatformLoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'staff@closeauth.test', 'correct-password')

    expect(router.currentRoute.value.path).toBe('/platform/console/tenants')
    expect(assignSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('FE-6.6: a cross-origin returnTo is refused — falls back to /platform/console instead', async () => {
    stubLoginFetch(() => ({
      ok: true,
      status: 200,
      body: {
        authenticated: true,
        adminId: 'admin-1',
        email: 'staff@closeauth.test',
        roles: ['PLATFORM_ADMIN'],
        accessTokenExpiresAt: '2026-01-01T00:05:00Z',
      },
    }))

    const router = await createLoginRouter(
      '/platform/login?returnTo=https%3A%2F%2Fevil.example.test%2Fsteal',
    )
    const wrapper = mount(PlatformLoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'staff@closeauth.test', 'correct-password')

    expect(router.currentRoute.value.path).toBe('/platform/console')
  })

  it('invalid credentials: uniform, enumeration-safe message, no navigation', async () => {
    stubLoginFetch(() => ({
      ok: false,
      status: 401,
      body: { error: 'invalid_credentials', error_description: 'Invalid credentials.' },
    }))

    const router = await createLoginRouter()
    const wrapper = mount(PlatformLoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'staff@closeauth.test', 'wrong-password')

    expect(router.currentRoute.value.path).toBe('/platform/login')
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).not.toContain('invalid_credentials')
    expect(alert.text()).toContain('Incorrect email or password')
  })

  it('FE-6.6: not_platform_admin gets the SAME uniform message as invalid credentials — a distinct string here is a pre-auth account-existence oracle', async () => {
    stubLoginFetch(() => ({
      ok: false,
      status: 403,
      body: { error: 'not_platform_admin', error_description: 'refused' },
    }))

    const router = await createLoginRouter()
    const wrapper = mount(PlatformLoginView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'noroles@closeauth.test', 'correct-password')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Incorrect email or password')
    expect(alert.text()).not.toContain('PLATFORM_ADMIN')
  })
})
