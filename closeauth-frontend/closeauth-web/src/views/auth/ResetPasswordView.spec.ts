import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import ResetPasswordView from './ResetPasswordView.vue'

// Stage UI-2c-i, Deliverable 4's required component tests for the confirm
// half of password reset: a successful reset (200), the generic
// invalid/expired/used-token error (400 — never enumerates which reason), a
// client-side password-mismatch guard, and the sessionStorage-driven
// post-success "log in" link restoration nice-to-have (falling back to a
// bare /login when nothing was saved, which is the normal, expected case
// for an email link opened in a fresh session).

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

async function createResetRouter(query: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/reset-password', component: ResetPasswordView },
    ],
  })
  await router.push(`/reset-password?${query}`)
  await router.isReady()
  return router
}

function stubFetch(confirmHandler: () => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/password-reset/confirm') return confirmHandler()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

beforeEach(() => {
  sessionStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, password: string, confirmPassword = password) {
  await wrapper.find('#reset-password-new').setValue(password)
  await wrapper.find('#reset-password-confirm').setValue(confirmPassword)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('ResetPasswordView', () => {
  it('a successful reset shows the "please log in" outcome, falling back to a bare /login with nothing saved', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch(confirm)

    const router = await createResetRouter('token=real-token-abc&client_id=client-a')
    const wrapper = mount(ResetPasswordView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Your password has been reset')
    // Nothing was saved via ForgotPasswordView/LoginView's sessionStorage
    // write in this test — the honest, common fallback: a bare /login.
    const loginLink = wrapper.find('a[href="/login"]')
    expect(loginLink.exists()).toBe(true)
  })

  it('restores the saved OAuth context on the post-success "log in" link when present in sessionStorage', async () => {
    sessionStorage.setItem('closeauth.forgotPassword.authorizeQuery', '?client_id=client-restored&state=st-2')
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch(confirm)

    const router = await createResetRouter('token=real-token-abc&client_id=client-a')
    const wrapper = mount(ResetPasswordView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    const loginLink = wrapper.findAll('a').find((a) => a.text().includes('Continue to sign in'))
    expect(loginLink?.attributes('href')).toBe('/login?client_id=client-restored&state=st-2')
  })

  it('a 400 (invalid/expired/used token) shows a generic inline error, never which reason', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 400 })
    stubFetch(confirm)

    const router = await createResetRouter('token=bad-token&client_id=client-a')
    const wrapper = mount(ResetPasswordView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invalid or has expired')
    // Never enumerates which specific reason.
    expect(alert.text()).not.toContain('expired_only')
    expect(alert.text()).not.toContain('already used')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(false)
  })

  it('a client-side password mismatch blocks submission before any fetch happens', async () => {
    const confirm = vi.fn().mockRejectedValue(new Error('should not be called'))
    stubFetch(confirm)

    const router = await createResetRouter('token=real-token-abc&client_id=client-a')
    const wrapper = mount(ResetPasswordView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!', 'Different-Pw-456!')

    expect(confirm).not.toHaveBeenCalled()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('do not match')
  })
})
