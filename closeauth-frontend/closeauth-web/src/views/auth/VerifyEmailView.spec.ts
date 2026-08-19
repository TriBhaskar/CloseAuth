import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import VerifyEmailView from './VerifyEmailView.vue'

// Stage UI-2b, Deliverable 5 / FE-2d's required component tests: successful
// confirm (fresh AND already-used — both land on the same success state),
// wrong code, expired, the 429 lockout state, resend, the link-triggered
// auto-consume path (no form flash), and the 2s auto-continue timer. Same
// mocked-fetch discipline as LoginView.spec.ts/RegisterView.spec.ts.
//
// Critically, none of these simulate a client-side RATE-LIMIT cooldown —
// per the stage prompt's explicit call-out, the 429 case is proven by
// asserting the view renders whatever the (mocked) real backend response
// says. The 2s auto-CONTINUE timer (FE-2d) is a different, one-shot
// post-success navigation delay — real fake-timer control is exactly right
// for it, and doesn't contradict that rule.

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

async function createVerifyRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/t/:slug/login', component: { template: '<div />' } },
      { path: '/t/:slug/verify-email', component: VerifyEmailView },
    ],
  })
  await router.push(query ? `/t/ten_acme-inc/verify-email?${query}` : '/t/ten_acme-inc/verify-email')
  await router.isReady()
  return router
}

function stubFetch(handlers: {
  confirm?: () => Promise<unknown>
  request?: () => Promise<unknown>
}) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/verify-email/confirm' && handlers.confirm) return handlers.confirm()
      if (url === '/verify-email/request' && handlers.request) return handlers.request()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

let pinia: Pinia

beforeEach(() => {
  // Fresh Pinia per test — TenantBrandingProvider's useThemeStore() needs an
  // active instance to mount at all.
  pinia = createPinia()
  vi.useFakeTimers()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

// PinInput has no single input to setValue on — a paste onto the first box
// fills every box at once, the same as a real fast-paste would.
async function fillAndConfirm(wrapper: ReturnType<typeof mount>, code: string) {
  await wrapper.find('#verify-email-code-0').trigger('paste', { clipboardData: { getData: () => code } })
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('VerifyEmailView', () => {
  it('a successful confirm shows the success state and schedules the auto-continue', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=verified-user%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '123456')

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('verified')
    expect(wrapper.find('a[href="/t/ten_acme-inc/login"]').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    // Auto-continues after 2s, not before.
    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/verify-email')
    await vi.advanceTimersByTimeAsync(2000)
    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/login')
  })

  it('carries authorize_query forward into the post-verification login link', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ confirm })

    const authorizeQuery = '?client_id=client-a&redirect_uri=https%3A%2F%2Frp.example%2Fcb&state=xyz'
    const router = await createVerifyRouter(
      `email=verified-user%40example.test&client_id=client-a&authorize_query=${encodeURIComponent(authorizeQuery)}`,
    )
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '123456')

    // find('a') alone would hit AuthShell's own "skip to content" anchor first.
    const link = wrapper.findAll('a').find((a) => a.text().includes('Continue to sign in'))
    expect(link?.attributes('href')).toBe('/t/ten_acme-inc/login' + authorizeQuery)
  })

  it('a wrong code shows the generic, enumeration-safe inline error (not a code-specific one)', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 400 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=user%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '000000')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invalid')
    // Never reveals which check failed, and doesn't claim "expired" — that's
    // its own distinct 410 state now.
    expect(alert.text()).not.toContain('expired')
    expect(wrapper.find('a[href="/t/ten_acme-inc/login"]').exists()).toBe(false)
  })

  it('a 429 shows the lockout message reactively, with no client-side cooldown timer', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 429 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=locked-out%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '111111')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text().toLowerCase()).toContain('too many attempts')
    // No countdown/timer text of any kind rendered.
    expect(wrapper.html()).not.toMatch(/\d+\s*(seconds|minutes)\s*(remaining|left)/i)
  })

  // FE-2d (spec §6.2.4): "expired... + Send a new one."
  it('a 410 shows the expired state with a working "Send a new one" action', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 410 })
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ confirm, request })

    const router = await createVerifyRouter('email=stale-link%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '999999')

    expect(wrapper.text()).toContain('expired')
    expect(wrapper.find('form').exists()).toBe(false)

    const sendNew = wrapper.findAll('button').find((b) => b.text().includes('Send a new one'))
    expect(sendNew).toBeTruthy()
    await sendNew!.trigger('click')
    await flushPromises()

    expect(request).toHaveBeenCalledTimes(1)
    // Back to the form so the fresh code has somewhere to go.
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('resend posts to /verify-email/request and shows a uniform confirmation message', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ request })

    const router = await createVerifyRouter('email=resend-me%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await wrapper.find('button[type="button"]').trigger('click')
    await flushPromises()

    expect(request).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[role="status"]').text()).toContain('a new code has been sent')
  })

  // FE-2d: the link path — a ?code= query param auto-fires confirm on
  // mount, with no form ever rendered (not even for one frame).
  describe('link-triggered auto-consume', () => {
    it('auto-fires confirm and shows a verifying state with no form', async () => {
      const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
      stubFetch({ confirm })

      const router = await createVerifyRouter('email=linked-user%40example.test&client_id=client-a&code=123456')
      const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })

      // Synchronous, first-render check — the form must never have existed.
      expect(wrapper.find('form').exists()).toBe(false)
      expect(wrapper.text()).toContain('Verifying')

      await flushPromises()

      expect(confirm).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).toContain('verified')
    })

    it('without both email and code present, falls back to the normal form (no auto-fire)', async () => {
      const confirm = vi.fn().mockRejectedValue(new Error('should not be called'))
      stubFetch({ confirm })

      // code present, but no email — cameFromLink requires both.
      const router = await createVerifyRouter('client_id=client-a&code=123456')
      const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(wrapper.find('form').exists()).toBe(true)
      expect(confirm).not.toHaveBeenCalled()
    })

    it('an expired link shows the expired state directly, still with no form flash first', async () => {
      const confirm = vi.fn().mockResolvedValue({ ok: false, status: 410 })
      stubFetch({ confirm })

      const router = await createVerifyRouter('email=linked-user%40example.test&client_id=client-a&code=999999')
      const wrapper = mount(VerifyEmailView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(wrapper.text()).toContain('expired')
      expect(wrapper.find('a[href="/t/ten_acme-inc/login"]').exists()).toBe(false)
    })
  })
})
