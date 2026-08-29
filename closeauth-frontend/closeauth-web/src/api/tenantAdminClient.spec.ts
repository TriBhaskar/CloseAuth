import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'
import { clearCsrfToken } from '@/api/csrf'
import router from '@/app/router'

// FE-1.12 (spec §7.2): "If a response's tenant_id doesn't match the route's
// tenant, the app treats it as a fatal integrity error: clear the session
// store, redirect to /t/{tenantId}, and log it." Checked in tenantAdminFetch
// itself (see that file's checkTenantMismatch) as a fire-and-forget check
// after the primary response is already resolved — flushPromises() a couple
// of times gives it room to run its own awaits (the body peek, the dynamic
// store/router imports) before assertions.
function activeSession(tenantId: string) {
  const store = useTenantAdminSessionStore()
  store.slug = 'acme'
  store.state = {
    kind: 'active',
    tenantId,
    userId: 'user-1',
    email: 'admin@acme.test',
    tenantRoles: ['TENANT_ADMIN'],
    accessTokenExpiresAt: '',
  }
  return store
}

function stubFetchReturning(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        clone() {
          return this
        },
        json: () =>
          body === undefined ? Promise.reject(new Error('no body')) : Promise.resolve(body),
      }),
    ),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('tenantAdminFetch — §7.2 tenant-mismatch fatal path', () => {
  it('a response carrying a DIFFERENT tenantId clears the session and redirects to the tenant resolver', async () => {
    const store = activeSession('tenant-expected')
    stubFetchReturning({ id: 'user-1', tenantId: 'tenant-WRONG' })
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await tenantAdminFetch('acme', '/users/user-1')
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('anonymous')
    expect(replaceSpy).toHaveBeenCalledWith('/t/acme')
    expect(errorSpy).toHaveBeenCalledOnce()
  })

  it('a response carrying the SAME tenantId is a no-op — no redirect, session untouched', async () => {
    const store = activeSession('tenant-expected')
    stubFetchReturning({ id: 'user-1', tenantId: 'tenant-expected' })
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)

    await tenantAdminFetch('acme', '/users/user-1')
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('active')
    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('a body with no tenantId field (e.g. a 204 lifecycle response) is a no-op', async () => {
    const store = activeSession('tenant-expected')
    stubFetchReturning(undefined, 204)
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)

    await tenantAdminFetch('acme', '/users/user-1/suspend', { method: 'POST' })
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('active')
    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('a non-ok response never triggers the mismatch check', async () => {
    activeSession('tenant-expected')
    stubFetchReturning({ error: 'not_found', error_description: 'no such user' }, 404)
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)

    await tenantAdminFetch('acme', '/users/does-not-exist')
    await flushPromises()
    await flushPromises()

    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('FE-6.6: a PageView list response with a mismatched tenantId on an ITEM (not the envelope) is caught — the highest-volume response shape', async () => {
    const store = activeSession('tenant-expected')
    stubFetchReturning({
      items: [
        { id: 'user-1', tenantId: 'tenant-expected' },
        { id: 'user-2', tenantId: 'tenant-WRONG' },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    })
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await tenantAdminFetch('acme', '/users')
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('anonymous')
    expect(replaceSpy).toHaveBeenCalledWith('/t/acme')
  })

  it('FE-6.6: a PageView list where every item matches is a no-op', async () => {
    const store = activeSession('tenant-expected')
    stubFetchReturning({
      items: [
        { id: 'user-1', tenantId: 'tenant-expected' },
        { id: 'user-2', tenantId: 'tenant-expected' },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    })
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)

    await tenantAdminFetch('acme', '/users')
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('active')
    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('FE-6.6: a response carrying tenant data with NO known session scope is treated as fatal, not silently skipped', async () => {
    // No activeSession() call — the store starts in its default (non-'active') state.
    const store = useTenantAdminSessionStore()
    stubFetchReturning({ id: 'user-1', tenantId: 'tenant-WRONG' })
    const replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await tenantAdminFetch('acme', '/users/user-1')
    await flushPromises()
    await flushPromises()

    expect(store.state.kind).toBe('anonymous')
    expect(replaceSpy).toHaveBeenCalledWith('/t/acme')
  })
})

describe('tenantAdminFetch — FE-6.6 reauthPath same-origin validation', () => {
  function stub401(reauthPath: string) {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 401,
          clone() {
            return this
          },
          json: () => Promise.resolve({ error: 'reauth_required', reauthPath }),
        }),
      ),
    )
  }

  beforeEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { assign: vi.fn(), pathname: '/t/acme/console/users', search: '' },
    })
  })

  it('a root-relative reauthPath navigates as before', async () => {
    stub401('/t/acme/admin/reauth')

    const result = await tenantAdminFetch('acme', '/users/user-1')

    expect(result.kind).toBe('reauth')
    expect(window.location.assign).toHaveBeenCalledWith(
      expect.stringContaining('/t/acme/admin/reauth?returnTo='),
    )
  })

  it('a protocol-relative ("//host/...") reauthPath is refused — an implicit cross-origin target', async () => {
    stub401('//evil.example.test/steal')

    const result = await tenantAdminFetch('acme', '/users/user-1')

    expect(window.location.assign).not.toHaveBeenCalled()
    expect(result.kind).toBe('response')
  })

  it('an absolute-URL reauthPath is refused', async () => {
    stub401('https://evil.example.test/steal')

    const result = await tenantAdminFetch('acme', '/users/user-1')

    expect(window.location.assign).not.toHaveBeenCalled()
    expect(result.kind).toBe('response')
  })
})
