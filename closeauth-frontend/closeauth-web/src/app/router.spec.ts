import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './router'

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
  // it), which calls useThemeStore() -> window.matchMedia(...) at store-
  // setup scope — needed here, not by any earlier spec in the suite.
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
  // FE-1.10: this is the one test in this file whose route resolution
  // requires Vue Router to await the dynamic import() of
  // layouts/TenantAdminLayout.vue's full module graph (ConsoleShell +
  // @vueuse/core's useMediaQuery + reka-ui's Sheet family) even though
  // nothing here ever actually mounts it into a DOM tree — this spec only
  // exercises the guard, never calls @vue/test-utils' mount(). That graph
  // is genuinely larger post-FE-1.10 (previously TenantAdminLayout +
  // TenantAdminSidebar only), so a cold first import under this test
  // suite's full parallel run can occasionally exceed the default 5s
  // timeout on a contended machine — confirmed by isolated runs and by
  // ConsoleShell.spec.ts's own dedicated, fast-passing mount tests; this is
  // margin for a legitimately bigger cold import, not a masked failure.
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
  }, 15000)

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

    expect(usersRoute?.meta.guard).toBe('tenantAdmin')
    expect(detailRoute?.meta.guard).toBe('tenantAdmin')
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

    expect(clientsRoute?.meta.guard).toBe('tenantAdmin')
    expect(credentialsRoute?.meta.guard).toBe('tenantAdmin')
    expect(clientDetailRoute?.meta.guard).toBe('tenantAdmin')
  })

  it('resource-servers and resource-server-detail routes require a tenant-admin session', () => {
    const listRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-resource-servers')
    const detailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-resource-server-detail')

    expect(listRoute?.meta.guard).toBe('tenantAdmin')
    expect(detailRoute?.meta.guard).toBe('tenantAdmin')
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
    expect(rolesRoute?.meta.guard).toBe('tenantAdmin')
  })

  it('the application-role detail route requires a tenant-admin session', () => {
    const appRoleDetailRoute = router.getRoutes().find((r) => r.name === 'tenant-admin-application-role-detail')
    expect(appRoleDetailRoute?.meta.guard).toBe('tenantAdmin')
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

    expect(tenantsRoute?.meta.guard).toBe('platformAdmin')
    expect(adminsRoute?.meta.guard).toBe('platformAdmin')
    expect(consoleRoute?.meta.guard).toBe('platformAdmin')
  })

  it('the login route is NOT gated (no meta.guard), unlike the console subtree', () => {
    const loginRoute = router.getRoutes().find((r) => r.name === 'platform-admin-login')
    expect(loginRoute?.meta.guard).toBeUndefined()
  })
})

// Phase 4a / BE-B: the tenant-onboarding password-rotation landing page must
// be reachable by an unauthenticated user with no session at all — no meta
// flag, so dispatchGuard (guards.ts) should never even inspect it (its
// early return for any route lacking meta.guard applies here). BE-B moved
// the path from /password-rotation to /t/:slug/password-rotation (spec
// §2.2) — PasswordRotationService.rotationPageUrl now emits exactly this
// shape.
describe('router password-rotation route (Phase 4a / BE-B)', () => {
  it('resolves with no guard meta and no redirect', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('fetch should not be called'))))

    await router.push('/t/ten_acme-inc/password-rotation?token=abc&client_id=admin-console-acme')

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/password-rotation')
    const route = router.getRoutes().find((r) => r.path === '/t/:slug/password-rotation')
    expect(route?.meta.guard).toBeUndefined()
    expect(window.location.assign).not.toHaveBeenCalled()
  })
})

// FE-1.12 (spec §7.3): an unknown URL must land on the not-found route, not
// silently bounce home — the catch-all's whole reason for changing.
describe('router catch-all and terminal pages (FE-1.12)', () => {
  it('an unmatched URL redirects to /not-found', async () => {
    await router.push('/this/path/does/not/exist')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('/not-found and /error resolve with no guard meta', () => {
    const notFound = router.getRoutes().find((r) => r.name === 'not-found')
    const errorRoute = router.getRoutes().find((r) => r.name === 'error')
    expect(notFound?.meta.guard).toBeUndefined()
    expect(errorRoute?.meta.guard).toBeUndefined()
  })
})
