import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import ConsentView from './ConsentView.vue'

// Stage UI-2c-ii (consent), Deliverable 2's required component test — per the
// stage prompt, THE test that matters most in this whole stage: proving the
// safety-critical property (CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md §1 Q4)
// actually holds in real rendered output, not just in the implementation's
// intent. The decision-submission forms must be genuine native HTML POSTs
// straight to the backend's own origin — never the BFF's, never relative,
// never intercepted by a @submit handler that would turn this into a
// fetch()-based mechanism and silently reintroduce the cross-origin cookie
// problem the whole design exists to avoid.

const AUTHORIZE_URL = 'http://backend.test:9000/closeauth/oauth2/authorize'

function brandingResponse(tenantSlug: string | null = null) {
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        logoUrl: '',
        primaryColor: '#4F46E5',
        backgroundColor: '#FFFFFF',
        accentColor: '#22D3EE',
        companyName: 'Acme Co',
        tenantSlug,
      }),
  })
}

function consentContextResponse() {
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        clientId: 'app-123',
        clientName: 'Acme App',
        state: 'st-abc123',
        scopes: [
          { scope: 'openid', description: 'Verify your identity', requiresConsent: false },
          { scope: 'todo-api:write', description: 'Modify your to-do items', requiresConsent: true },
        ],
        alreadyGranted: [],
        // THE field the BFF proxy injects (handlers_consent_proxy.go) — the
        // real backend origin, never the BFF's own.
        authorizeUrl: AUTHORIZE_URL,
      }),
  })
}

// BE-B: /consent (where SAS actually lands the browser — see ConsentView.vue's
// header comment) AND /t/:slug/consent (the spec-shaped landing the component
// redirects to once branding resolves a slug) — same component at both.
async function createConsentRouter(initialPath = '/consent') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/consent', component: ConsentView },
      { path: '/t/:slug/consent', component: ConsentView },
    ],
  })
  await router.push(initialPath)
  await router.isReady()
  return router
}

let locationSearch: string
let pinia: Pinia

beforeEach(() => {
  locationSearch = '?client_id=app-123&scope=openid%20todo-api%3Awrite&state=st-abc123'
  // Fresh Pinia per test — TenantBrandingProvider's useThemeStore() needs an
  // active instance to mount at all.
  pinia = createPinia()
  // Same stubbing convention LoginView.spec.ts established: jsdom's real
  // Location object doesn't allow freely reassigning `.search`, and
  // ConsentView.vue (like LoginView.vue before it) reads window.location.
  // search directly, not route.query.
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      get search() {
        return locationSearch
      },
      set search(value: string) {
        locationSearch = value
      },
    },
  })

  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url.startsWith('/oauth2/consent')) return consentContextResponse()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function mountConsentView(initialPath = '/consent') {
  const router = await createConsentRouter(initialPath)
  const wrapper = mount(ConsentView, { global: { plugins: [router, pinia] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('ConsentView', () => {
  it('fetches the context proxy with the captured query string', async () => {
    const wrapper = await mountConsentView()
    const fetchMock = window.fetch as unknown as ReturnType<typeof vi.fn>
    const consentCalls = fetchMock.mock.calls.filter((call: unknown[]) =>
      (call[0] as string).startsWith('/oauth2/consent'),
    )
    expect(consentCalls).toHaveLength(1)
    expect(consentCalls[0]?.[0]).toBe('/oauth2/consent?client_id=app-123&scope=openid%20todo-api%3Awrite&state=st-abc123')
    expect(wrapper.text()).toContain('Acme App')
  })

  it('THE safety-critical test: both the approve and deny forms POST natively to the backend\'s own origin, never the BFF/relative, and are never intercepted by JavaScript', async () => {
    const wrapper = await mountConsentView()

    const approveForm = wrapper.find('[data-testid="approve-form"]')
    const denyForm = wrapper.find('[data-testid="deny-form"]')
    expect(approveForm.exists()).toBe(true)
    expect(denyForm.exists()).toBe(true)

    // The core assertion: `action` is the backend's own absolute origin from
    // the context response — never empty, never a bare/relative path (which
    // would resolve against the BFF's own origin), never the BFF's origin.
    expect(approveForm.attributes('action')).toBe(AUTHORIZE_URL)
    expect(approveForm.attributes('method')).toBe('post')
    expect(denyForm.attributes('action')).toBe(AUTHORIZE_URL)
    expect(denyForm.attributes('method')).toBe('post')

    // Neither action attribute is a bare path or empty string — both
    // explicitly cross-origin relative to this SPA's own origin.
    expect(approveForm.attributes('action')).not.toBe('')
    expect(approveForm.attributes('action')?.startsWith('/')).toBe(false)
    expect(denyForm.attributes('action')).not.toBe('')
    expect(denyForm.attributes('action')?.startsWith('/')).toBe(false)

    // Proves no @submit handler intercepts this navigation: dispatch a real
    // 'submit' DOM event directly on the underlying <form> element (bypassing
    // Vue Test Utils' own `trigger`, which is fine either way, to be
    // unambiguous about testing the raw DOM) and confirm nothing called
    // preventDefault() on it. If ConsentView.vue had a `@submit.prevent`
    // handler (the exact anti-pattern this design forbids — a fetch()-based
    // interception), this assertion would fail.
    for (const form of [approveForm, denyForm]) {
      const formEl = form.element as HTMLFormElement
      const event = new Event('submit', { cancelable: true, bubbles: true })
      formEl.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(false)
    }
  })

  it('the deny form carries ONLY client_id and state — structurally no scope input at all, regardless of the approve form\'s checkbox state', async () => {
    const wrapper = await mountConsentView()

    // Even with the approve form's checkbox checked, the deny form (a
    // genuinely separate <form> element) cannot be affected — there is no
    // shared state between them because they are not the same form.
    await wrapper.find('input[type="checkbox"][value="todo-api:write"]').setValue(true)

    const denyForm = wrapper.find('[data-testid="deny-form"]')
    expect(denyForm.findAll('input[name="scope"]')).toHaveLength(0)
    expect(denyForm.find('input[name="client_id"]').attributes('value')).toBe('app-123')
    expect(denyForm.find('input[name="state"]').attributes('value')).toBe('st-abc123')
  })

  it('the approve form renders an unchecked checkbox for the requires-consent scope and a hidden always-submitted input for the auto-granted scope', async () => {
    const wrapper = await mountConsentView()

    const approveForm = wrapper.find('[data-testid="approve-form"]')

    const checkbox = approveForm.find('input[type="checkbox"][value="todo-api:write"]')
    expect(checkbox.exists()).toBe(true)
    expect(checkbox.attributes('name')).toBe('scope')
    expect((checkbox.element as HTMLInputElement).checked).toBe(false)

    const hidden = approveForm.find('input[type="hidden"][value="openid"]')
    expect(hidden.exists()).toBe(true)
    expect(hidden.attributes('name')).toBe('scope')

    expect(approveForm.find('input[name="client_id"]').attributes('value')).toBe('app-123')
    expect(approveForm.find('input[name="state"]').attributes('value')).toBe('st-abc123')
  })

  it('shows a generic inline error on a context-fetch failure, and renders no forms at all', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse()
        if (url.startsWith('/oauth2/consent')) return Promise.resolve({ ok: false, status: 400 })
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = await mountConsentView()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="deny-form"]').exists()).toBe(false)
  })
})

// FE-2f: the display requirements spec §6.2.8 names beyond the
// safety-critical form shape proven above — the "Requested access" box's raw
// scope string, alreadyGranted pre-check + label, the description fallback,
// and the client-initial avatar. The existing 8 tests above are untouched.
describe('ConsentView — FE-2f display requirements', () => {
  it('shows each scope\'s raw scope string in mono alongside its description', async () => {
    const wrapper = await mountConsentView()

    const text = wrapper.text()
    expect(text).toContain('openid')
    expect(text).toContain('todo-api:write')
    expect(text).toContain('Verify your identity')
    expect(text).toContain('Modify your to-do items')
  })

  it('renders the "Requested access" box heading', async () => {
    const wrapper = await mountConsentView()
    expect(wrapper.text()).toContain('Requested access')
  })

  it('pre-checks a requires-consent scope present in alreadyGranted and labels it "Previously approved"', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse()
        if (url.startsWith('/oauth2/consent')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                clientId: 'app-123',
                clientName: 'Acme App',
                state: 'st-abc123',
                scopes: [
                  { scope: 'openid', description: 'Verify your identity', requiresConsent: false },
                  { scope: 'todo-api:write', description: 'Modify your to-do items', requiresConsent: true },
                ],
                // Stubbed non-empty — the only way to exercise this path
                // today; through the real BFF proxy this is always empty
                // (the tracked identity gap, see the plan).
                alreadyGranted: ['todo-api:write'],
                authorizeUrl: AUTHORIZE_URL,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = await mountConsentView()

    const checkbox = wrapper.find('input[type="checkbox"][value="todo-api:write"]')
    expect((checkbox.element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('Previously approved')
  })

  it('does NOT show "Previously approved" for a scope absent from alreadyGranted', async () => {
    const wrapper = await mountConsentView()
    expect(wrapper.text()).not.toContain('Previously approved')
    const checkbox = wrapper.find('input[type="checkbox"][value="todo-api:write"]')
    expect((checkbox.element as HTMLInputElement).checked).toBe(false)
  })

  it('falls back to the raw scope string when a description is empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse()
        if (url.startsWith('/oauth2/consent')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                clientId: 'app-123',
                clientName: 'Acme App',
                state: 'st-abc123',
                scopes: [{ scope: 'weird-rs:custom-scope', description: '', requiresConsent: true }],
                alreadyGranted: [],
                authorizeUrl: AUTHORIZE_URL,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = await mountConsentView()
    // The scope string appears twice when the description is empty: once as
    // the fallback label, once as the always-shown mono scope string.
    expect(wrapper.text().match(/weird-rs:custom-scope/g)?.length).toBe(2)
  })

  it('renders a client-initial avatar when there is no client logo', async () => {
    const wrapper = await mountConsentView()
    // hasLogo is false for every stubbed branding response in this file
    // (logoUrl: '') — the initial avatar is the only fallback.
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('A') // clientName 'Acme App' → 'A'
  })
})

// BE-B: the two-hop redirect to /t/{slug}/consent — see ConsentView.vue's
// own header comment for why SAS can only ever land the browser on the
// fixed, un-namespaced /consent, and why this client-side hop exists.
//
// Each test below uses its OWN client_id, distinct from every other test in
// this file (which all share 'app-123') and from each other: api/publicBranding.ts's
// fetch cache is module-level and keyed by client_id, persisting across
// tests within this file — reusing a client_id another test already
// resolved branding for would silently serve THAT test's cached tenantSlug
// instead of this test's own stub.
describe('ConsentView — BE-B tenant-namespaced redirect', () => {
  it('mounted at the un-namespaced /consent, redirects to /t/{slug}/consent once branding resolves a slug', async () => {
    locationSearch = '?client_id=app-redirect-yes&scope=openid&state=st-redirect-yes'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse('ten_acme-inc')
        if (url.startsWith('/oauth2/consent')) return consentContextResponse()
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createConsentRouter('/consent')
    mount(ConsentView, { global: { plugins: [router, pinia] } })
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/consent')
    expect(router.currentRoute.value.fullPath).toBe(
      '/t/ten_acme-inc/consent?client_id=app-redirect-yes&scope=openid&state=st-redirect-yes',
    )
  })

  it('mounted at the un-namespaced /consent, stays put when branding resolves no slug (unknown client_id)', async () => {
    locationSearch = '?client_id=app-redirect-no&scope=openid&state=st-redirect-no'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse(null)
        if (url.startsWith('/oauth2/consent')) return consentContextResponse()
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createConsentRouter('/consent')
    mount(ConsentView, { global: { plugins: [router, pinia] } })
    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/consent')
  })

  it('mounted directly at /t/{slug}/consent, never redirects (already namespaced)', async () => {
    locationSearch = '?client_id=app-redirect-already&scope=openid&state=st-redirect-already'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) return brandingResponse('ten_acme-inc')
        if (url.startsWith('/oauth2/consent')) return consentContextResponse()
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createConsentRouter('/t/ten_acme-inc/consent')
    const wrapper = mount(ConsentView, { global: { plugins: [router, pinia] } })
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/consent')
    // The real consent UI still renders normally at the namespaced path.
    expect(wrapper.find('[data-testid="approve-form"]').exists()).toBe(true)
  })
})
