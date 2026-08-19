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
        json: () => (body === undefined ? Promise.reject(new Error('no body')) : Promise.resolve(body)),
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
})
