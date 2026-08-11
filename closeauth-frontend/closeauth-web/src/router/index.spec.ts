import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './index'

// Stage UI-3a's required guard test: the repo's first router.beforeEach.
// Exercises all four fetchSession outcomes plus the invalid-slug
// short-circuit, asserting exactly what navigates (a real top-level
// window.location.assign, never a fetch-driven redirect) and what doesn't.

beforeEach(async () => {
  setActivePinia(createPinia())
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { assign: vi.fn(), pathname: '/', search: '' },
  })
  // jsdom doesn't implement matchMedia at all. This spec is the first to
  // actually resolve TenantAdminLayout.vue (the 'active' outcome renders
  // it), which calls useColorScheme() -> window.matchMedia(...) at module
  // scope — needed here, not by any earlier spec in the suite.
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  })
  await router.push('/')
  await router.isReady()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubSessionFetch(body: unknown, status = 200) {
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

describe('router tenant-admin guard', () => {
  it('active session: renders the console route, no navigation', async () => {
    stubSessionFetch({
      slug: 'acme',
      authenticated: true,
      tenantId: 't1',
      userId: 'u1',
      email: 'a@acme.test',
      tenantRoles: ['TENANT_ADMIN'],
      accessTokenExpiresAt: '',
    })

    await router.push('/t/acme/console')

    expect(router.currentRoute.value.name).toBe('tenant-admin-console')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('anonymous: performs a full-page navigation to admin/login with returnTo', async () => {
    stubSessionFetch({ slug: 'acme', authenticated: false })

    await router.push('/t/acme/console')

    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/login?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('reauth required: performs a full-page navigation to admin/reauth with returnTo', async () => {
    stubSessionFetch({ slug: 'acme', authenticated: true, reauthRequired: true })

    await router.push('/t/acme/console')

    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/reauth?returnTo=%2Ft%2Facme%2Fconsole')
  })

  it('denied: lands on the denied route with the reason, and NO location assignment', async () => {
    stubSessionFetch({ slug: 'acme', authenticated: false, denied: true, deniedReason: 'not_tenant_admin' })

    await router.push('/t/acme/console')

    expect(router.currentRoute.value.name).toBe('tenant-admin-denied')
    expect(router.currentRoute.value.query.reason).toBe('not_tenant_admin')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unreachable BFF: lands on auth-error without a location assignment', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    await router.push('/t/acme/console')

    expect(router.currentRoute.value.name).toBe('tenant-admin-auth-error')
    expect(router.currentRoute.value.query.reason).toBe('bff_unreachable')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('invalid slug short-circuits to auth-error without ever calling fetch', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    await router.push('/t/BAD_SLUG!/console')

    expect(router.currentRoute.value.name).toBe('tenant-admin-auth-error')
    expect(router.currentRoute.value.query.reason).toBe('invalid_slug')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

// Stage UI-3b: the users-list and user-detail routes are guarded the same
// way the console landing route is — a static route-table check (not a full
// navigation) is enough to prove the meta flag is wired; the guard's actual
// behavior for each session outcome is already covered above.
describe('router tenant-admin CRUD routes (UI-3b)', () => {
  it('users and user-detail routes require a tenant-admin session', () => {
    const usersRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-users')
    const detailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-user-detail')

    expect(usersRoute?.meta.requiresTenantAdmin).toBe(true)
    expect(detailRoute?.meta.requiresTenantAdmin).toBe(true)
  })
})

// Stage UI-3c: same discipline for the clients/resource-servers routes,
// plus proof that the static clients/credentials segment resolves to its
// own route rather than being swallowed by the clients/:clientId dynamic
// segment (Vue Router ranks static segments over dynamic ones regardless of
// declaration order, but this is cheap to prove directly rather than trust).
describe('router tenant-admin clients/resource-servers routes (UI-3c)', () => {
  it('clients, client-credentials, and client-detail routes require a tenant-admin session', () => {
    const clientsRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-clients')
    const credentialsRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-client-credentials')
    const clientDetailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-client-detail')

    expect(clientsRoute?.meta.requiresTenantAdmin).toBe(true)
    expect(credentialsRoute?.meta.requiresTenantAdmin).toBe(true)
    expect(clientDetailRoute?.meta.requiresTenantAdmin).toBe(true)
  })

  it('resource-servers and resource-server-detail routes require a tenant-admin session', () => {
    const listRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-resource-servers')
    const detailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-resource-server-detail')

    expect(listRoute?.meta.requiresTenantAdmin).toBe(true)
    expect(detailRoute?.meta.requiresTenantAdmin).toBe(true)
  })

  it('clients/credentials resolves to the credentials route, not clients/:clientId', () => {
    const resolved = router.resolve('/t/acme/console/clients/credentials')
    expect(resolved.name).toBe('tenant-admin-client-credentials')
  })
})

// Stage UI-3d: the tenant-role list route and the application-role detail
// route (nested under resource-servers/:rsId, mirroring the backend's own
// RS-scoped URL shape) — same static route-table check as the prior stages.
describe('router tenant-admin roles routes (UI-3d)', () => {
  it('the tenant-roles list route requires a tenant-admin session', () => {
    const rolesRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-roles')
    expect(rolesRoute?.meta.requiresTenantAdmin).toBe(true)
  })

  it('the application-role detail route requires a tenant-admin session', () => {
    const appRoleDetailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-application-role-detail')
    expect(appRoleDetailRoute?.meta.requiresTenantAdmin).toBe(true)
  })

  it('resource-servers/:rsId/roles/:roleId resolves to the application-role detail route, not the RS detail route', () => {
    const resolved = router.resolve('/t/acme/console/resource-servers/rs-1/roles/role-1')
    expect(resolved.name).toBe('tenant-admin-application-role-detail')
    expect(resolved.params).toEqual({ slug: 'acme', rsId: 'rs-1', roleId: 'role-1' })
  })
})

// Stage UI-4: the platform-admin console's guard branch. Genuinely simpler
// than the tenant branch above by construction — no slug, no denied/reauth
// outcomes (PlatformAdminSessionState has only active/anonymous/unreachable)
// — and its anonymous/unreachable redirect is a client-side router.push to
// /platform/login, NOT window.location.assign: there is no backend SSO entry
// point for an anonymous visitor to be sent to (see the guard's own comment
// in index.ts for why).
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

describe('router platform-admin guard (UI-4)', () => {
  it('active session: renders the platform console route, no window.location.assign', async () => {
    stubPlatformSessionFetch({
      authenticated: true,
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    })

    await router.push('/platform/console')

    expect(router.currentRoute.value.name).toBe('platform-admin-console')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('anonymous: client-side redirects to platform-admin-login with returnTo, no window.location.assign', async () => {
    stubPlatformSessionFetch({ authenticated: false })

    await router.push('/platform/console/tenants')

    expect(router.currentRoute.value.name).toBe('platform-admin-login')
    expect(router.currentRoute.value.query.returnTo).toBe('/platform/console/tenants')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unreachable BFF: also lands on platform-admin-login (no separate error page on this surface)', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    await router.push('/platform/console/admins')

    expect(router.currentRoute.value.name).toBe('platform-admin-login')
  })

  it('the tenants and admins routes require a platform-admin session', () => {
    const tenantsRoute = router.getRoutes().find((r) => r.name === 'platform-admin-tenants')
    const adminsRoute = router.getRoutes().find((r) => r.name === 'platform-admin-admins')
    const consoleRoute = router.getRoutes().find((r) => r.name === 'platform-admin-console')

    expect(tenantsRoute?.meta.requiresPlatformAdmin).toBe(true)
    expect(adminsRoute?.meta.requiresPlatformAdmin).toBe(true)
    expect(consoleRoute?.meta.requiresPlatformAdmin).toBe(true)
  })

  it('the login route is NOT gated (no requiresPlatformAdmin), unlike the console subtree', () => {
    const loginRoute = router.getRoutes().find((r) => r.name === 'platform-admin-login')
    expect(loginRoute?.meta.requiresPlatformAdmin).toBeUndefined()
  })
})

// Phase 4a: the tenant-onboarding password-rotation landing page must be
// reachable by an unauthenticated user with no session at all — no meta
// flag, so the guard above should never even inspect it (the guard's early
// exit at `if (!to.meta.requiresTenantAdmin) return true` applies to any
// route lacking both meta flags).
describe('router password-rotation route (Phase 4a)', () => {
  it('resolves with no guard meta and no redirect', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('fetch should not be called'))))

    await router.push('/password-rotation?token=abc&client_id=admin-console-acme')

    expect(router.currentRoute.value.path).toBe('/password-rotation')
    const route = router.getRoutes().find((r) => r.path === '/password-rotation')
    expect(route?.meta.requiresTenantAdmin).toBeUndefined()
    expect(route?.meta.requiresPlatformAdmin).toBeUndefined()
    expect(window.location.assign).not.toHaveBeenCalled()
  })
})
