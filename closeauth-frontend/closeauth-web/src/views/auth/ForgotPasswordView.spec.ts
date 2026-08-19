import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import ForgotPasswordView from './ForgotPasswordView.vue'

// Stage UI-2c-i, Deliverable 4's required component tests for the request
// half of password reset: the success branch (POST /password-reset/request
// resolves 200 — the backend's own enumeration-safe uniform response) and
// the error branch (a genuine transport failure).
//
// Also proves the sessionStorage carry-through nice-to-have: submitting
// writes the current query string so ResetPasswordView.vue can attempt to
// restore it later in the same session (passwordResetContext.ts).

function brandingResponse() {
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

async function createForgotPasswordRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/t/:slug/login', component: { template: '<div />' } },
      { path: '/t/:slug/forgot-password', component: ForgotPasswordView },
    ],
  })
  await router.push(query ? `/t/ten_acme-inc/forgot-password?${query}` : '/t/ten_acme-inc/forgot-password')
  await router.isReady()
  return router
}

function stubFetch(requestHandler: () => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/password-reset/request') return requestHandler()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

let pinia: Pinia

beforeEach(() => {
  sessionStorage.clear()
  // Fresh Pinia per test — TenantBrandingProvider's useThemeStore() needs an
  // active instance to mount at all.
  pinia = createPinia()
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, email: string) {
  await wrapper.find('#forgot-password-email').setValue(email)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('ForgotPasswordView', () => {
  it('a successful request shows the enumeration-safe "check your email" confirmation', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch(request)

    const router = await createForgotPasswordRouter('client_id=client-a')
    const wrapper = mount(ForgotPasswordView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test')

    expect(request).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('If that account exists, we\'ve sent reset instructions.')
    // Enumeration-safe: never confirms or denies the account exists.
    expect(wrapper.text()).not.toContain('does not exist')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('a genuine transport failure shows a generic inline error, not the confirmation', async () => {
    const request = vi.fn().mockRejectedValue(new Error('network down'))
    stubFetch(request)

    const router = await createForgotPasswordRouter('client_id=client-a')
    const wrapper = mount(ForgotPasswordView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Something went wrong')
    expect(wrapper.find('#forgot-password-email').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('reset instructions')
  })

  it('submitting saves the current query string to sessionStorage for later restoration', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch(request)

    const router = await createForgotPasswordRouter('client_id=client-restore&state=st-1')
    const wrapper = mount(ForgotPasswordView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(sessionStorage.getItem('closeauth.forgotPassword.authorizeQuery')).toBeNull()

    await fillAndSubmit(wrapper, 'user@example.test')

    expect(sessionStorage.getItem('closeauth.forgotPassword.authorizeQuery')).toBe(window.location.search)
  })
})
