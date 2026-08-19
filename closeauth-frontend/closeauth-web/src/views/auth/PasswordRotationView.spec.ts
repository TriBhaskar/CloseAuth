import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import PasswordRotationView from './PasswordRotationView.vue'

// FE-2.5 (spec §6.2.7): the security property proven at the component level
// (the backend/BFF proofs live in PasswordRotationControllerTest.java and
// authPasswordRotation.ts's own doc comments) — no navigation, no
// cookie-dependent state change, and no success-shaped UI on anything short
// of a genuine `{ok:true, redirectTo}` result.

let hrefAssignments: string[]
let pinia: Pinia

function stubBrandingFetch(loginFetchHandler?: (init?: RequestInit) => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
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
              companyName: 'Acme Inc',
              tenantSlug: 'ten_acme-inc',
              registrationMode: null,
            }),
        })
      }
      if (url === '/api/auth/password-rotation/confirm' && loginFetchHandler) {
        return loginFetchHandler(init)
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

async function mountAt(search: string, loginFetchHandler?: (init?: RequestInit) => Promise<unknown>) {
  stubBrandingFetch(loginFetchHandler)
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      get href() {
        return hrefAssignments.at(-1) ?? ''
      },
      set href(value: string) {
        hrefAssignments.push(value)
      },
      search,
    },
  })

  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/password-rotation', component: PasswordRotationView },
      { path: '/t/:slug/login', component: { template: '<div />' } },
    ],
  })
  await router.push('/t/ten_acme-inc/password-rotation' + search)
  await router.isReady()

  const wrapper = mount(PasswordRotationView, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return wrapper
}

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, password = 'new-password-123') {
  await wrapper.find('#password-rotation-new').setValue(password)
  await wrapper.find('#password-rotation-confirm').setValue(password)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

beforeEach(() => {
  hrefAssignments = []
  pinia = createPinia()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PasswordRotationView', () => {
  it('an incomplete link (missing token) never renders a form', async () => {
    const wrapper = await mountAt('?client_id=admin-console-acme')

    expect(wrapper.text()).toContain('This link is incomplete')
    expect(wrapper.find('#password-rotation-new').exists()).toBe(false)
  })

  it('an incomplete link (missing client_id) never renders a form', async () => {
    const wrapper = await mountAt('?token=tok-1')

    expect(wrapper.text()).toContain('This link is incomplete')
    expect(wrapper.find('#password-rotation-new').exists()).toBe(false)
  })

  it('a successful confirm navigates to the backend-provided redirectTo — never router.push', async () => {
    const confirmFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'https://backend.test/closeauth/oauth2/authorize?resume=1' }),
    })
    const wrapper = await mountAt('?token=tok-1&client_id=admin-console-acme', confirmFetch)

    await fillAndSubmit(wrapper)

    expect(confirmFetch).toHaveBeenCalledTimes(1)
    const [init] = confirmFetch.mock.calls[0] as [RequestInit]
    const body = JSON.parse(init.body as string)
    expect(body).toMatchObject({ token: 'tok-1', clientId: 'admin-console-acme', password: 'new-password-123' })
    expect(hrefAssignments).toEqual(['https://backend.test/closeauth/oauth2/authorize?resume=1'])
  })

  it('an invalid/expired token shows the generic inline error and never navigates', async () => {
    const confirmFetch = vi.fn().mockResolvedValue({ ok: false, status: 400, json: () => Promise.resolve({}) })
    const wrapper = await mountAt('?token=bad-tok&client_id=admin-console-acme', confirmFetch)

    await fillAndSubmit(wrapper)

    expect(hrefAssignments).toEqual([])
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invalid or has expired')
    // Still the form, not the "your password was updated" success copy.
    expect(wrapper.find('#password-rotation-new').exists()).toBe(true)
  })

  it('a network failure shows a generic retry error and never navigates', async () => {
    const confirmFetch = vi.fn().mockRejectedValue(new Error('network down'))
    const wrapper = await mountAt('?token=tok-1&client_id=admin-console-acme', confirmFetch)

    await fillAndSubmit(wrapper)

    expect(hrefAssignments).toEqual([])
    expect(wrapper.find('[role="alert"]').text()).toContain('Something went wrong')
  })

  it('a 200 with no redirectTo shows the "updated but could not return you" state, not an error', async () => {
    const confirmFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) })
    const wrapper = await mountAt('?token=tok-1&client_id=admin-console-acme', confirmFetch)

    await fillAndSubmit(wrapper)

    expect(hrefAssignments).toEqual([])
    expect(wrapper.text()).toContain('Your password was updated')
    expect(wrapper.find('#password-rotation-new').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
