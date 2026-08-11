import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PasswordRotationView from './PasswordRotationView.vue'

// Phase 4a: the landing page for both tenant-onboarding password-rotation
// on-ramps. Follows ResetPasswordView.spec.ts's idioms (brandingResponse
// stub, url-prefix fetch dispatch) plus LoginView.spec.ts's window.location
// stub — needed here because, like LoginView (and unlike ResetPasswordView),
// this view reads window.location.search directly rather than route.query,
// and asserts window.location.href on success.

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

async function createRotationRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/password-rotation', component: PasswordRotationView },
    ],
  })
  await router.push('/password-rotation')
  await router.isReady()
  return router
}

let hrefAssignments: string[]
let locationSearch: string

beforeEach(() => {
  hrefAssignments = []
  locationSearch = ''
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

function stubFetch(confirmHandler: (init?: RequestInit) => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/api/auth/password-rotation/confirm') return confirmHandler(init)
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

async function mountRotationView() {
  const router = await createRotationRouter()
  const wrapper = mount(PasswordRotationView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, password: string, confirmPassword = password) {
  await wrapper.find('#password-rotation-new').setValue(password)
  await wrapper.find('#password-rotation-confirm').setValue(confirmPassword)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('PasswordRotationView', () => {
  it('forwards authorize_query verbatim after exactly one decode — the round-trip proof', async () => {
    // Q1 is what LoginController.buildAuthorizeQuery would have produced —
    // a real query string, already once URL-encoded (the '+' inside the
    // state value is pre-encoded as %2B, exactly as URLEncoder would emit
    // it), containing its own structural & and =.
    const q1 = 'client_id=c-abc&state=xy%2Bz'
    // What actually appears in the address bar: Q1 encoded a SECOND time as
    // one opaque authorize_query value (PasswordRotationService.enc()).
    locationSearch = `?token=tok-1&client_id=admin-console-acme&authorize_query=${encodeURIComponent(q1)}`

    const confirm = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'http://backend.test/closeauth/oauth2/authorize?resume=1' }),
    })
    stubFetch(confirm)

    const wrapper = await mountRotationView()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    expect(confirm).toHaveBeenCalledTimes(1)
    const [init] = confirm.mock.calls[0] as [RequestInit]
    const body = JSON.parse(init.body as string)
    // Decoded EXACTLY once — matches Q1 verbatim, not re-encoded, not
    // double-decoded (which would have mangled the embedded %2B).
    expect(body.authorizeQuery).toBe(q1)
    expect(body.token).toBe('tok-1')
    expect(body.clientId).toBe('admin-console-acme')
  })

  it('navigates to the backend-provided redirectTo on success (temp-password on-ramp)', async () => {
    locationSearch = '?token=tok-1&client_id=admin-console-acme&authorize_query=client_id%3Dc-abc'
    const confirm = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'http://backend.test/closeauth/oauth2/authorize?resume=1' }),
    })
    stubFetch(confirm)

    const wrapper = await mountRotationView()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    expect(hrefAssignments).toEqual(['http://backend.test/closeauth/oauth2/authorize?resume=1'])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('emailed-link on-ramp: no authorize_query in the URL, still navigates on success', async () => {
    locationSearch = '?token=tok-1&client_id=admin-console-acme'
    const confirm = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ redirectTo: 'http://bff.test/' }),
    })
    stubFetch(confirm)

    const wrapper = await mountRotationView()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    expect(confirm).toHaveBeenCalledTimes(1)
    const [init] = confirm.mock.calls[0] as [RequestInit]
    const body = JSON.parse(init.body as string)
    expect(body.authorizeQuery).toBeUndefined()
    expect(hrefAssignments).toEqual(['http://bff.test/'])
  })

  it('a 400 (invalid/expired/consumed token) shows a generic banner pointing at the administrator, never a reason', async () => {
    locationSearch = '?token=bad-token&client_id=admin-console-acme'
    const confirm = vi.fn().mockResolvedValue({ ok: false, status: 400 })
    stubFetch(confirm)

    const wrapper = await mountRotationView()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invalid or has expired')
    expect(alert.text()).toContain('administrator')
    expect(alert.text()).not.toContain('expired_only')
    expect(hrefAssignments).toEqual([])
  })

  it('a 200 with an empty redirectTo shows the "already changed, sign in" state, not a failure', async () => {
    locationSearch = '?token=tok-1&client_id=admin-console-acme'
    const confirm = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) })
    stubFetch(confirm)

    const wrapper = await mountRotationView()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!')

    expect(hrefAssignments).toEqual([])
    expect(wrapper.text()).toContain('Your password was updated')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
  })

  it('a missing token shows the incomplete-link state and never calls fetch', async () => {
    locationSearch = '?client_id=admin-console-acme'
    const confirm = vi.fn()
    stubFetch(confirm)

    const wrapper = await mountRotationView()

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('incomplete')
    expect(confirm).not.toHaveBeenCalled()
  })

  it('a missing client_id shows the incomplete-link state and never calls fetch', async () => {
    locationSearch = '?token=tok-1'
    const confirm = vi.fn()
    stubFetch(confirm)

    const wrapper = await mountRotationView()

    expect(wrapper.find('form').exists()).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
  })

  it('a mismatched confirm field and a too-short password both block submission before any fetch', async () => {
    locationSearch = '?token=tok-1&client_id=admin-console-acme'
    const confirm = vi.fn()
    stubFetch(confirm)

    const wrapper = await mountRotationView()

    await fillAndSubmit(wrapper, 'short')
    expect(confirm).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('at least 8 characters')

    await fillAndSubmit(wrapper, 'Long-Enough-Pw-1', 'Different-Pw-2')
    expect(confirm).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('do not match')
  })
})
