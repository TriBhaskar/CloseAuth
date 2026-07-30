import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import VerifyEmailView from './VerifyEmailView.vue'

// Stage UI-2b, Deliverable 5's required component tests: successful
// confirm, wrong code, and the 429 lockout state — plus a resend-action
// check, since the view's whole point is being independently reachable with
// its own actions, not just a confirm form. Same mocked-fetch discipline as
// LoginView.spec.ts/RegisterView.spec.ts.
//
// Critically, NONE of these tests simulate a client-side cooldown timer —
// per the stage prompt's explicit call-out, the 429 case here is proven by
// asserting the view renders whatever the (mocked) real backend response
// says, with no fake clock, no setTimeout assertions, nothing to drift out
// of sync with a real rate limiter.

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
      { path: '/login', component: { template: '<div />' } },
      { path: '/verify-email', component: VerifyEmailView },
    ],
  })
  await router.push(query ? `/verify-email?${query}` : '/verify-email')
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

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillAndConfirm(wrapper: ReturnType<typeof mount>, code: string) {
  await wrapper.find('#verify-email-code').setValue(code)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('VerifyEmailView', () => {
  it('a successful confirm shows the same "please log in" outcome as registration success', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=verified-user%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '123456')

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('verified')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('a wrong code shows the generic, enumeration-safe inline error (not a code-specific one)', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 400 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=user%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '000000')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invalid or has expired')
    // Never reveals which check failed.
    expect(alert.text()).not.toContain('expired_only')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(false)
  })

  it('a 429 shows the lockout message reactively, with no client-side cooldown timer', async () => {
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 429 })
    stubFetch({ confirm })

    const router = await createVerifyRouter('email=locked-out%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndConfirm(wrapper, '111111')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text().toLowerCase()).toContain('too many attempts')
    // No countdown/timer text of any kind rendered.
    expect(wrapper.html()).not.toMatch(/\d+\s*(seconds|minutes)\s*(remaining|left)/i)
  })

  it('resend posts to /verify-email/request and shows a uniform confirmation message', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch({ request })

    const router = await createVerifyRouter('email=resend-me%40example.test&client_id=client-a')
    const wrapper = mount(VerifyEmailView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('button[type="button"]').trigger('click')
    await flushPromises()

    expect(request).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[role="status"]').text()).toContain('a new code has been sent')
  })
})
