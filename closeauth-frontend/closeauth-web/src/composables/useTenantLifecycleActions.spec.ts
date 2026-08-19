import { describe, it, expect, vi, afterEach } from 'vitest'
import { useTenantLifecycleActions } from './useTenantLifecycleActions'
import type { TenantView } from '@/api/platformAdminTenants'

// FE-3b: the composable extracted from PlatformTenantsView.vue (FE-3a) once
// the tenant detail page became a genuine second consumer. Tested directly,
// independent of either view.

function tenantFixture(overrides: Partial<TenantView> = {}): TenantView {
  return {
    id: 'tenant-1',
    slug: 'ten_acme-inc',
    name: 'Acme Inc',
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    adminCount: 1,
    ...overrides,
  }
}

function stubFetch(handler: (url: string, init?: RequestInit) => { ok: boolean; status: number; body: unknown } | null) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/csrf') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
      }
      const result = handler(url, init)
      if (result) {
        return Promise.resolve({ ok: result.ok, status: result.status, json: () => Promise.resolve(result.body), clone() { return this } })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useTenantLifecycleActions', () => {
  it('startAction populates confirmState from the tenant and the requested action', () => {
    const onSuccess = vi.fn()
    const { confirmState, startAction } = useTenantLifecycleActions(onSuccess)

    startAction(tenantFixture(), 'suspend')

    expect(confirmState.value).toEqual({
      tenantId: 'tenant-1', slug: 'ten_acme-inc', name: 'Acme Inc', action: 'suspend',
    })
  })

  it('cancelAction clears confirmState without calling the API', () => {
    const onSuccess = vi.fn()
    const { confirmState, startAction, cancelAction } = useTenantLifecycleActions(onSuccess)
    startAction(tenantFixture(), 'delete')

    cancelAction()

    expect(confirmState.value).toBeNull()
  })

  it('confirmTitle/confirmDescription are spec-literal for suspend, analogous for activate', () => {
    const onSuccess = vi.fn()
    const { startAction, confirmTitle, confirmDescription } = useTenantLifecycleActions(onSuccess)

    startAction(tenantFixture({ status: 'ACTIVE' }), 'suspend')
    expect(confirmTitle.value).toBe('Suspend Acme Inc?')
    expect(confirmDescription.value).toBe("Users won't be able to sign in and all active tokens stop working immediately.")

    startAction(tenantFixture({ status: 'SUSPENDED' }), 'activate')
    expect(confirmTitle.value).toBe('Activate Acme Inc?')
    expect(confirmDescription.value).toContain('becomes ACTIVE')
  })

  it('isConfirmDialogOpen/isDeleteDialogOpen route suspend+activate vs delete to the right dialog', () => {
    const onSuccess = vi.fn()
    const { startAction, isConfirmDialogOpen, isDeleteDialogOpen } = useTenantLifecycleActions(onSuccess)

    startAction(tenantFixture(), 'suspend')
    expect(isConfirmDialogOpen.value).toBe(true)
    expect(isDeleteDialogOpen.value).toBe(false)

    startAction(tenantFixture(), 'delete')
    expect(isConfirmDialogOpen.value).toBe(false)
    expect(isDeleteDialogOpen.value).toBe(true)
  })

  it('confirmAction on success clears confirmState and calls onSuccess with the action that just succeeded', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/suspend' && init?.method === 'POST') {
        return { ok: true, status: 200, body: tenantFixture({ status: 'SUSPENDED' }) }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { confirmState, actionError, startAction, confirmAction } = useTenantLifecycleActions(onSuccess)
    startAction(tenantFixture(), 'suspend')

    await confirmAction()

    expect(confirmState.value).toBeNull()
    expect(actionError.value).toBe('')
    expect(onSuccess).toHaveBeenCalledExactlyOnceWith('suspend')
  })

  it('confirmAction on failure sets actionError and leaves confirmState (dialog stays open, retryable)', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/suspend' && init?.method === 'POST') {
        return { ok: false, status: 500, body: { error: 'server_error', error_description: 'boom' } }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { confirmState, actionError, startAction, confirmAction } = useTenantLifecycleActions(onSuccess)
    startAction(tenantFixture(), 'suspend')

    await confirmAction()

    expect(confirmState.value).not.toBeNull()
    expect(actionError.value).not.toBe('')
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('confirmAction does nothing when no action is pending', async () => {
    const onSuccess = vi.fn()
    const { confirmAction } = useTenantLifecycleActions(onSuccess)

    await confirmAction()

    expect(onSuccess).not.toHaveBeenCalled()
  })
})
