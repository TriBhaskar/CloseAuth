import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import MagicLinkRequestView from './MagicLinkRequestView.vue'

// Stage UI-2c-i, Deliverable 3's required component tests (Vue Test Utils,
// mocked fetch — same discipline as VerifyEmailView.spec.ts/
// RegisterView.spec.ts): the success branch (POST /magic-link/request
// resolves 200 — the backend's own enumeration-safe uniform response) and
// the error branch (a genuine transport failure, the only way this view's
// error state is ever reached, since the backend itself never distinguishes
// existing/nonexistent accounts).

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

async function createMagicLinkRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/magic-link-request', component: MagicLinkRequestView },
    ],
  })
  await router.push(query ? `/magic-link-request?${query}` : '/magic-link-request')
  await router.isReady()
  return router
}

function stubFetch(requestHandler: () => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/magic-link/request') return requestHandler()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, email: string) {
  await wrapper.find('#magic-link-email').setValue(email)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('MagicLinkRequestView', () => {
  it('a successful request shows the enumeration-safe "check your email" confirmation', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    stubFetch(request)

    const router = await createMagicLinkRouter('client_id=client-a')
    const wrapper = mount(MagicLinkRequestView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test')

    expect(request).toHaveBeenCalledTimes(1)
    expect(wrapper.text().toLowerCase()).toContain('sign-in link')
    // Enumeration-safe: never confirms or denies the account exists.
    expect(wrapper.text()).not.toContain('does not exist')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('a genuine transport failure shows a generic inline error, not the confirmation', async () => {
    const request = vi.fn().mockRejectedValue(new Error('network down'))
    stubFetch(request)

    const router = await createMagicLinkRouter('client_id=client-a')
    const wrapper = mount(MagicLinkRequestView, { global: { plugins: [router] } })
    await flushPromises()

    await fillAndSubmit(wrapper, 'user@example.test')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Something went wrong')
    // Still on the form — no confirmation state rendered.
    expect(wrapper.find('#magic-link-email').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('sign-in link to it')
  })
})
