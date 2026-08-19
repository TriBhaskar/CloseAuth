import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import TenantResolverView from './TenantResolverView.vue'

// FE-2a (spec §6.2.1): proves the resolver's four outcomes purely through
// its own effects (navigation target / window.location.href) — it renders
// no interactive UI to assert against, only a loading indicator.

let hrefAssignments: string[]
let pinia: Pinia

async function createResolverRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug', component: TenantResolverView },
      { path: '/t/:slug/console', component: { template: '<div />' } },
      { path: '/t/:slug/account', component: { template: '<div />' } },
      { path: '/t/:slug/auth-error', component: { template: '<div />' } },
      { path: '/t/:slug/denied', component: { template: '<div />' } },
    ],
  })
  await router.push('/t/ten_acme-inc')
  await router.isReady()
  return router
}

function stubFetch(handlers: Record<string, () => Promise<unknown>>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      for (const [prefix, handler] of Object.entries(handlers)) {
        if (url.startsWith(prefix)) return handler()
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

function okJson(body: unknown) {
  return () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
}

function notFound() {
  return () => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) })
}

beforeEach(() => {
  hrefAssignments = []
  pinia = createPinia()
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

describe('TenantResolverView', () => {
  it('unknown tenant -> auth-error with reason=unknown_tenant, unbranded (no /branding call)', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.startsWith('/api/entry/resolve')) return notFound()()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/auth-error')
    expect(router.currentRoute.value.query.reason).toBe('unknown_tenant')
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/branding'), expect.anything())
  })

  it('resolveTenant transport error -> auth-error with reason=bff_unreachable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/api/entry/resolve')) return Promise.reject(new Error('network down'))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/auth-error')
    expect(router.currentRoute.value.query.reason).toBe('bff_unreachable')
  })

  it('active TENANT_ADMIN session -> /t/{tenantId}/console', async () => {
    stubFetch({
      '/api/entry/resolve': okJson({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
      '/branding': okJson({
        logoUrl: '',
        primaryColor: '',
        backgroundColor: '',
        accentColor: '',
        companyName: 'Acme Inc',
        tenantSlug: 'ten_acme-inc',
      }),
      '/t/ten_acme-inc/api/session': okJson({
        slug: 'ten_acme-inc',
        authenticated: true,
        tenantId: 'tenant-1',
        userId: 'user-1',
        email: 'ada@acme.test',
        tenantRoles: ['TENANT_ADMIN'],
      }),
    })

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/console')
  })

  // FE-4d: 'active' (non-admin) is now a REAL, distinguishable outcome —
  // the callback fix means a non-admin gets a genuine session instead of
  // being refused one. Supersedes the old "denied means non-admin" test
  // below, whose own premise this fix intentionally overturns.
  it('active session with NO TENANT_ADMIN role -> /t/{tenantId}/account', async () => {
    stubFetch({
      '/api/entry/resolve': okJson({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
      '/branding': okJson({
        logoUrl: '',
        primaryColor: '',
        backgroundColor: '',
        accentColor: '',
        companyName: 'Acme Inc',
        tenantSlug: 'ten_acme-inc',
      }),
      '/t/ten_acme-inc/api/session': okJson({
        slug: 'ten_acme-inc',
        authenticated: true,
        tenantId: 'tenant-1',
        userId: 'user-1',
        email: 'nonadmin@acme.test',
        tenantRoles: [],
      }),
    })

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/account')
  })

  // FE-4d: 'denied' now only ever means the callback's binding checks
  // failed (a client_id/tenant_id mismatch) — genuinely rare in honest use,
  // and no longer conflated with "authenticated but not an admin."
  it('denied session -> /t/{tenantId}/denied with the reason', async () => {
    stubFetch({
      '/api/entry/resolve': okJson({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
      '/branding': okJson({
        logoUrl: '',
        primaryColor: '',
        backgroundColor: '',
        accentColor: '',
        companyName: 'Acme Inc',
        tenantSlug: 'ten_acme-inc',
      }),
      '/t/ten_acme-inc/api/session': okJson({
        slug: 'ten_acme-inc',
        authenticated: false,
        denied: true,
        deniedReason: 'invalid_client_binding',
      }),
    })

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/denied')
    expect(router.currentRoute.value.query.reason).toBe('invalid_client_binding')
  })

  it('no session -> starts a fresh authorize flow and navigates via window.location.href', async () => {
    stubFetch({
      '/api/entry/resolve': okJson({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
      '/branding': okJson({
        logoUrl: '',
        primaryColor: '',
        backgroundColor: '',
        accentColor: '',
        companyName: 'Acme Inc',
        tenantSlug: 'ten_acme-inc',
      }),
      '/t/ten_acme-inc/api/session': okJson({ slug: 'ten_acme-inc', authenticated: false }),
      '/api/auth/authorize/start': okJson({ authorizeUrl: 'https://backend.test/oauth2/authorize?client_id=admin-console-ten_acme-inc' }),
    })

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(hrefAssignments.at(-1)).toBe('https://backend.test/oauth2/authorize?client_id=admin-console-ten_acme-inc')
    // Never itself navigates via router for a successful authorize start —
    // the real top-level navigation above is what leaves the SPA.
    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc')
  })

  it('no session, authorize-start rate-limited -> auth-error with reason=rate_limited', async () => {
    stubFetch({
      '/api/entry/resolve': okJson({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
      '/branding': okJson({
        logoUrl: '',
        primaryColor: '',
        backgroundColor: '',
        accentColor: '',
        companyName: 'Acme Inc',
        tenantSlug: 'ten_acme-inc',
      }),
      '/t/ten_acme-inc/api/session': okJson({ slug: 'ten_acme-inc', authenticated: false }),
      '/api/auth/authorize/start': () => Promise.resolve({ ok: false, status: 429, json: () => Promise.resolve(null) }),
    })

    const router = await createResolverRouter()
    mount(TenantResolverView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/auth-error')
    expect(router.currentRoute.value.query.reason).toBe('rate_limited')
  })
})
