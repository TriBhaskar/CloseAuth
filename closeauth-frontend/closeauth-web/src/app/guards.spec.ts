import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'
import { requiresPlatformAdmin, requiresTenantAdmin, requiresTenantSession, dispatchGuard } from '@/app/guards'

// FE-1.12 (spec §7.1): unit-level coverage for each guard function
// directly, calling the exported functions rather than navigating a real
// router — app/router.spec.ts already covers the full-navigation path
// (rendering, redirects observed via router.currentRoute) for the same
// session outcomes; this file proves the GUARD CONTRACT itself (what each
// one returns/does per outcome) more cheaply and in isolation.
function fakeRoute(overrides: Partial<RouteLocationNormalized> = {}): RouteLocationNormalized {
  return {
    fullPath: '/t/acme/console',
    params: { slug: 'acme' },
    meta: {},
    ...overrides,
  } as unknown as RouteLocationNormalized
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { assign: vi.fn(), pathname: '/', search: '' },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubPlatformSessionFetch(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/platform/api/session') {
        return Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

function stubTenantSessionFetch(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/t/acme/api/session') {
        return Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

describe('requiresPlatformAdmin', () => {
  it('active session returns true', async () => {
    stubPlatformSessionFetch({
      authenticated: true,
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: '',
    })

    const result = await requiresPlatformAdmin(fakeRoute({ fullPath: '/platform/console/tenants' }))
    expect(result).toBe(true)
  })

  it('anonymous returns a client-side redirect with returnTo, no window.location.assign', async () => {
    stubPlatformSessionFetch({ authenticated: false })

    const result = await requiresPlatformAdmin(fakeRoute({ fullPath: '/platform/console/tenants' }))
    expect(result).toEqual({ name: 'platform-admin-login', query: { returnTo: '/platform/console/tenants' } })
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unreachable BFF also redirects to platform-admin-login (no separate error page on this surface)', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await requiresPlatformAdmin(fakeRoute({ fullPath: '/platform/console' }))
    expect(result).toEqual({ name: 'platform-admin-login', query: { returnTo: '/platform/console' } })
  })
})

describe('requiresTenantAdmin', () => {
  it('active session returns true', async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'a@acme.test',
      tenantRoles: ['TENANT_ADMIN'],
      accessTokenExpiresAt: '',
    })

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toBe(true)
  })

  it('denied returns a redirect to the denied route with the reason', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: false, denied: true, deniedReason: 'not_tenant_admin' })

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toEqual({ name: 'tenant-admin-denied', params: { slug: 'acme' }, query: { reason: 'not_tenant_admin' } })
  })

  it('unreachable BFF redirects to auth-error with bff_unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toEqual({ name: 'tenant-admin-auth-error', params: { slug: 'acme' }, query: { reason: 'bff_unreachable' } })
  })

  it('anonymous performs a real top-level navigation and returns false', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: false })

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toBe(false)
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/login?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('reauth required performs a real top-level navigation to the reauth path', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: true, reauthRequired: true })

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toBe(false)
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/reauth?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('an invalid slug short-circuits to auth-error without ever calling fetch', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    const result = await requiresTenantAdmin(fakeRoute({ params: { slug: 'BAD_SLUG!' } }))
    expect(result).toEqual({ name: 'tenant-admin-auth-error', params: { slug: 'unknown' }, query: { reason: 'invalid_slug' } })
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  // FE-4d: 'active' no longer implies admin — the callback fix lets a
  // non-admin tenant user reach a real session too. This guard must not let
  // them through to an admin-guarded route.
  // Bug fix: a real post-BE-A tenant slug contains an underscore
  // ("ten_rohit") — the old SLUG_PATTERN had no ten_-prefixed alternative
  // and rejected it outright, before a session lookup was ever attempted.
  it('a ten_-prefixed slug is accepted — not short-circuited to auth-error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/ten_rohit/api/session') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                slug: 'ten_rohit',
                authenticated: true,
                tenantId: 't1',
                userId: 'u1',
                email: 'a@rohit.test',
                tenantRoles: ['TENANT_ADMIN'],
                accessTokenExpiresAt: '',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const result = await requiresTenantAdmin(fakeRoute({ params: { slug: 'ten_rohit' } }))
    expect(result).toBe(true)
  })

  it('active session with NO TENANT_ADMIN role redirects to the account page, never renders admin content', async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'nonadmin@acme.test',
      tenantRoles: [],
      accessTokenExpiresAt: '',
    })

    const result = await requiresTenantAdmin(fakeRoute())
    expect(result).toEqual({ name: 'tenant-account', params: { slug: 'acme' } })
  })
})

describe('requiresTenantSession', () => {
  it('active session with NO TENANT_ADMIN role still returns true — this guard is deliberately role-agnostic', async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'nonadmin@acme.test',
      tenantRoles: [],
      accessTokenExpiresAt: '',
    })

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toBe(true)
  })

  it('active session WITH TENANT_ADMIN role also returns true — admins can reach /account too', async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'admin@acme.test',
      tenantRoles: ['TENANT_ADMIN'],
      accessTokenExpiresAt: '',
    })

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toBe(true)
  })

  it('denied returns a redirect to the denied route with the reason', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: false, denied: true, deniedReason: 'invalid_client_binding' })

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toEqual({
      name: 'tenant-admin-denied',
      params: { slug: 'acme' },
      query: { reason: 'invalid_client_binding' },
    })
  })

  it('unreachable BFF redirects to auth-error with bff_unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toEqual({ name: 'tenant-admin-auth-error', params: { slug: 'acme' }, query: { reason: 'bff_unreachable' } })
  })

  it('anonymous performs a real top-level navigation and returns false', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: false })

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toBe(false)
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/login?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('reauth required performs a real top-level navigation to the reauth path', async () => {
    stubTenantSessionFetch({ slug: 'acme', authenticated: true, reauthRequired: true })

    const result = await requiresTenantSession(fakeRoute())
    expect(result).toBe(false)
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/reauth?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('an invalid slug short-circuits to auth-error without ever calling fetch', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    const result = await requiresTenantSession(fakeRoute({ params: { slug: 'BAD_SLUG!' } }))
    expect(result).toEqual({ name: 'tenant-admin-auth-error', params: { slug: 'unknown' }, query: { reason: 'invalid_slug' } })
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

describe('dispatchGuard', () => {
  it('a route with no meta.guard is requiresNone — always true, no fetch', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    const result = await dispatchGuard(fakeRoute({ meta: {} }))
    expect(result).toBe(true)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it("meta.guard === 'platformAdmin' dispatches to requiresPlatformAdmin", async () => {
    stubPlatformSessionFetch({
      authenticated: true,
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: '',
    })

    const result = await dispatchGuard(fakeRoute({ meta: { guard: 'platformAdmin' }, fullPath: '/platform/console' }))
    expect(result).toBe(true)
  })

  it("meta.guard === 'tenantAdmin' dispatches to requiresTenantAdmin", async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'a@acme.test',
      tenantRoles: ['TENANT_ADMIN'],
      accessTokenExpiresAt: '',
    })

    const result = await dispatchGuard(fakeRoute({ meta: { guard: 'tenantAdmin' } }))
    expect(result).toBe(true)
  })

  it("meta.guard === 'tenantSession' dispatches to requiresTenantSession", async () => {
    stubTenantSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'nonadmin@acme.test',
      tenantRoles: [],
      accessTokenExpiresAt: '',
    })

    const result = await dispatchGuard(fakeRoute({ meta: { guard: 'tenantSession' } }))
    expect(result).toBe(true)
  })
})
