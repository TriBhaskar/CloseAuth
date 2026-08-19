import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantUserDetailView from './TenantUserDetailView.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { clearCsrfToken } from '@/api/csrf'

// Stage UI-3b: the detail view's required proofs — lifecycle actions match
// the real transition matrix (never an action the backend would refuse), a
// 409 tenant_role.last_admin renders the SPECIFIC message rather than a
// generic conflict, role checkboxes reflect real fetched state, and a held
// role name absent from the catalog is rendered (disabled, explained), not
// dropped.
//
// Stage UI-3d extends stubBaseFetch with the application-roles panel's RS
// list and adds its own proofs (RS selector, per-RS held-role loading, the
// same unresolved-role honesty rule extended per-RS).
//
// FE-4a: the page is now tabbed (Profile · Roles · Sessions) — tests that
// exercise Roles/Sessions content explicitly switch tabs first via the real
// TabsTrigger, rather than assuming inactive-tab content stays queryable in
// the DOM (Tabs/TabsList/TabsTrigger/TabsContent are the real reka-ui
// components here, unlike Dialog — Tabs has no portal/focus-trap risk, so
// this is its first real, non-hand-rolled adoption). Also adds: the
// pre-emptive last-admin block (Suspend/Delete disabled + explained BEFORE
// any click, driven by UserView.isLastActiveAdmin), the Sessions tab itself,
// and per-row (not panel-wide) pending state on role toggles.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createDetailRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/users', name: 'tenant-admin-users', component: { template: '<div />' } },
      { path: '/t/:slug/console/users/:userId', name: 'tenant-admin-user-detail', component: TenantUserDetailView },
    ],
  })
  await router.push('/t/acme/console/users/user-1')
  await router.isReady()
  return router
}

function userFixture(status: string, isLastActiveAdmin: boolean | null = null) {
  return {
    id: 'user-1',
    tenantId: 'tenant-1',
    email: 'alice@acme.test',
    emailVerified: true,
    phone: null,
    phoneVerified: false,
    firstName: 'Alice',
    lastName: 'Admin',
    status,
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    roles: [],
    isLastActiveAdmin,
  }
}

const roleCatalog = [
  { id: 'role-admin', tenantId: 'tenant-1', name: 'TENANT_ADMIN', description: null, isDefault: false, isSystem: true, createdAt: '', updatedAt: '' },
  { id: 'role-member', tenantId: 'tenant-1', name: 'TENANT_MEMBER', description: null, isDefault: true, isSystem: true, createdAt: '', updatedAt: '' },
  { id: 'role-billing', tenantId: 'tenant-1', name: 'BILLING_ADMIN', description: null, isDefault: false, isSystem: true, createdAt: '', updatedAt: '' },
]

const resourceServers = [
  { id: 'rs-1', tenantId: 'tenant-1', slug: 'billing-api', name: 'Billing API', audienceIdentifier: 'https://acme.rs.closeauth.io/billing-api', autoCreated: false, createdAt: '', updatedAt: null },
]

function stubBaseFetch(status: string, heldRoleNames: string[], opts: { isLastActiveAdmin?: boolean | null; sessions?: unknown[] } = {}) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/t/acme/api/users/user-1') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(userFixture(status, opts.isLastActiveAdmin ?? null)) })
    }
    if (url === '/t/acme/api/roles?page=0&size=100') {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: roleCatalog, page: 0, size: 100, totalElements: 3, totalPages: 1 }),
      })
    }
    if (url === '/t/acme/api/users/user-1/tenant-roles') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(heldRoleNames) })
    }
    if (url === '/t/acme/api/resource-servers?page=0&size=100') {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: resourceServers, page: 0, size: 100, totalElements: 1, totalPages: 1 }),
      })
    }
    if (url === '/t/acme/api/users/user-1/sessions') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(opts.sessions ?? []) })
    }
    if (url === '/api/csrf') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
    }
    return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
  })
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantUserDetailView', () => {
  it('ACTIVE user: offers suspend/delete only (the real transition matrix)', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', ['TENANT_MEMBER']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#user-action-suspend').exists()).toBe(true)
    expect(wrapper.find('#user-action-delete').exists()).toBe(true)
    expect(wrapper.find('#user-action-approve').exists()).toBe(false)
    expect(wrapper.find('#user-action-activate').exists()).toBe(false)
  })

  it('PENDING user: offers approve/delete only', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('PENDING', []))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#user-action-approve').exists()).toBe(true)
    expect(wrapper.find('#user-action-delete').exists()).toBe(true)
    expect(wrapper.find('#user-action-suspend').exists()).toBe(false)
    expect(wrapper.find('#user-action-activate').exists()).toBe(false)
  })

  it('DELETED user: offers no actions at all (terminal)', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('DELETED', []))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#user-action-approve').exists()).toBe(false)
    expect(wrapper.find('#user-action-activate').exists()).toBe(false)
    expect(wrapper.find('#user-action-suspend').exists()).toBe(false)
    expect(wrapper.find('#user-action-delete').exists()).toBe(false)
  })

  // ---- FE-4a: pre-emptive last-admin block --------------------------------

  it('the last active admin: Suspend and Delete are disabled with an explanation BEFORE any click', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', ['TENANT_ADMIN'], { isLastActiveAdmin: true }))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const suspend = wrapper.find('#user-action-suspend')
    const del = wrapper.find('#user-action-delete')
    expect(suspend.attributes('disabled')).toBeDefined()
    expect(del.attributes('disabled')).toBeDefined()
    expect(suspend.attributes('title')).toBe('This tenant needs at least one active admin.')
    expect(wrapper.text()).toContain('This tenant needs at least one active admin.')
    // Not a dialog that opened and then refused — clicking does nothing.
    await suspend.trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('a non-last admin: Suspend and Delete are fully enabled', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', ['TENANT_ADMIN'], { isLastActiveAdmin: false }))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#user-action-suspend').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('#user-action-delete').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('This tenant needs at least one active admin.')
  })

  // ---- roles (tab) ---------------------------------------------------------

  it('roles panel reflects real held state, and a held-but-uncataloged role is shown disabled with an explanation, never dropped', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', ['TENANT_MEMBER', 'GHOST_ROLE']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    // Catalog role held -> checked.
    const memberCheckbox = wrapper.find('#role-role-member')
    expect(memberCheckbox.attributes('aria-checked')).toBe('true')
    // Catalog role not held -> unchecked.
    const billingCheckbox = wrapper.find('#role-role-billing')
    expect(billingCheckbox.attributes('aria-checked')).toBe('false')

    // GHOST_ROLE: held per the backend, absent from the fetched catalog —
    // must still be visible (never silently dropped), with an explanation.
    expect(wrapper.text()).toContain('GHOST_ROLE')
    expect(wrapper.text()).toContain('held — not in the role catalog')
  })

  it('toggling an unheld role assigns it via the tenant-roles endpoint', async () => {
    const fetchMock = stubBaseFetch('ACTIVE', ['TENANT_MEMBER'])
    const withAssign = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/users/user-1/tenant-roles/role-billing' && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withAssign)

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#role-role-billing').trigger('click')
    await flushPromises()

    expect(withAssign).toHaveBeenCalledWith(
      '/t/acme/api/users/user-1/tenant-roles/role-billing',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('FE-4a: only the toggled role is disabled while its call is in flight — a sibling checkbox stays interactive (§7.5: disable the trigger, never the whole form)', async () => {
    const fetchMock = stubBaseFetch('ACTIVE', ['TENANT_MEMBER'])
    let resolveAssign: (() => void) | null = null
    const withAssign = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/users/user-1/tenant-roles/role-billing' && init?.method === 'POST') {
        return new Promise((resolve) => {
          resolveAssign = () => resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
        })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withAssign)

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#role-role-billing').trigger('click')
    await flushPromises()

    // In-flight: BILLING_ADMIN's own checkbox is disabled...
    expect(wrapper.find('#role-role-billing').attributes('disabled')).toBeDefined()
    // ...but TENANT_ADMIN's checkbox (untouched) is not.
    expect(wrapper.find('#role-role-admin').attributes('disabled')).toBeUndefined()

    resolveAssign!()
    await flushPromises()
    expect(wrapper.find('#role-role-billing').attributes('disabled')).toBeUndefined()
  })

  it('409 tenant_role.last_admin on suspend renders the SPECIFIC message, not a generic conflict banner', async () => {
    const fetchMock = stubBaseFetch('ACTIVE', ['TENANT_ADMIN'])
    const withSuspend = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/users/user-1/suspend' && init?.method === 'POST') {
        return Promise.resolve({
          ok: false,
          status: 409,
          json: () =>
            Promise.resolve({
              error: 'tenant_role.last_admin',
              error_description: "Cannot remove the last TENANT_ADMIN from tenant tenant-1",
            }),
        })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withSuspend)

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#user-action-suspend').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain("last active administrator")
    // The generic conflict message must NOT be the one shown.
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })

  it('shows a visible error, no user card, when the user fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users/user-1') {
          return Promise.resolve({
            ok: false,
            status: 404,
            json: () => Promise.resolve({ error: 'user.not_found', error_description: 'User not found.' }),
          })
        }
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: roleCatalog, page: 0, size: 100, totalElements: 3, totalPages: 1 }),
          })
        }
        if (url === '/t/acme/api/users/user-1/tenant-roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/t/acme/api/users/user-1/sessions') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('User not found.')
    expect(wrapper.find('#user-detail-email').exists()).toBe(false)
  })

  // ---- application roles (UI-3d) -----------------------------------------

  const appRoleCatalog = [
    { id: 'app-role-reader', resourceServerId: 'rs-1', tenantId: 'tenant-1', name: 'INVOICE_READER', description: null, isDefault: false, isSystem: false, createdAt: '', updatedAt: '' },
    { id: 'app-role-admin', resourceServerId: 'rs-1', tenantId: 'tenant-1', name: 'INVOICE_ADMIN', description: null, isDefault: false, isSystem: false, createdAt: '', updatedAt: '' },
  ]

  function stubWithAppRoles(status: string, heldRoleNames: string[], heldAppRoleNames: string[]) {
    const base = stubBaseFetch(status, heldRoleNames)
    return vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/resource-servers/rs-1/roles?page=0&size=20') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ items: appRoleCatalog, page: 0, size: 20, totalElements: 2, totalPages: 1 }),
        })
      }
      if (url === '/t/acme/api/users/user-1/application-roles?resourceServerId=rs-1') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(heldAppRoleNames) })
      }
      return base(url, init)
    })
  }

  it('application-roles panel renders an RS selector fed by the real resource-server list', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', ['TENANT_MEMBER']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    const select = wrapper.find('#app-role-rs-select')
    expect(select.exists()).toBe(true)
    expect(select.text()).toContain('Billing API')
  })

  it('selecting a resource server loads its application-role catalog and held names', async () => {
    vi.stubGlobal('fetch', stubWithAppRoles('ACTIVE', ['TENANT_MEMBER'], ['INVOICE_READER']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#app-role-rs-select').setValue('rs-1')
    await flushPromises()

    const heldCheckbox = wrapper.find('#app-role-app-role-reader')
    expect(heldCheckbox.attributes('aria-checked')).toBe('true')
    const unheldCheckbox = wrapper.find('#app-role-app-role-admin')
    expect(unheldCheckbox.attributes('aria-checked')).toBe('false')
  })

  it('a held application-role name absent from the RS catalog is shown disabled and explained, never dropped', async () => {
    vi.stubGlobal('fetch', stubWithAppRoles('ACTIVE', ['TENANT_MEMBER'], ['INVOICE_READER', 'GHOST_APP_ROLE']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="roles"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#app-role-rs-select').setValue('rs-1')
    await flushPromises()

    expect(wrapper.text()).toContain('GHOST_APP_ROLE')
    expect(wrapper.text()).toContain("not in this resource server's role catalog")
  })

  // ---- sessions (new, FE-4a) -----------------------------------------------

  const sessionFixture = {
    id: 'session-1',
    rememberMe: false,
    ipAddress: '203.0.113.5',
    userAgent: 'Mozilla/5.0 (Macintosh)',
    amr: 'pwd',
    createdAt: '2026-01-01T00:00:00Z',
    idleExpiresAt: '2026-01-01T01:00:00Z',
    absoluteExpiresAt: '2026-01-02T00:00:00Z',
    lastAccessedAt: '2026-01-01T00:30:00Z',
  }

  it('sessions tab: renders the device list from a real fetch', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', [], { sessions: [sessionFixture] }))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="sessions"]').trigger('mousedown')
    await flushPromises()

    const row = wrapper.find('[data-session-id="session-1"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('Mozilla/5.0 (Macintosh)')
    expect(row.text()).toContain('203.0.113.5')
  })

  it('sessions tab: no sessions shows an honest empty state, not a fabricated row', async () => {
    vi.stubGlobal('fetch', stubBaseFetch('ACTIVE', [], { sessions: [] }))

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="sessions"]').trigger('mousedown')
    await flushPromises()

    expect(wrapper.text()).toContain('No active sessions.')
    expect(wrapper.find('#revoke-all-sessions-button').exists()).toBe(false)
  })

  it('sessions tab: revoking one session calls the per-session endpoint and refreshes the list', async () => {
    const fetchMock = stubBaseFetch('ACTIVE', [], { sessions: [sessionFixture] })
    let revokedOnce = false
    const withRevoke = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/users/user-1/sessions/session-1' && init?.method === 'DELETE') {
        revokedOnce = true
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
      }
      if (url === '/t/acme/api/users/user-1/sessions' && !init?.method) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(revokedOnce ? [] : [sessionFixture]),
        })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withRevoke)

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="sessions"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#revoke-session-session-1').trigger('click')
    await flushPromises()
    // Two ConfirmDialog instances exist on this page (lifecycle + per-
    // session revoke) and dialogStubs makes both always render regardless
    // of `open`, so #confirm-dialog-confirm is not unique — scope to the
    // one whose title actually matches this action.
    const revokeDialog = wrapper
      .findAllComponents(ConfirmDialog)
      .find((c) => c.props('title') === 'Revoke this session?')
    expect(revokeDialog).toBeTruthy()
    await revokeDialog!.get('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(withRevoke).toHaveBeenCalledWith(
      '/t/acme/api/users/user-1/sessions/session-1',
      expect.objectContaining({ method: 'DELETE' }),
    )
    expect(wrapper.text()).toContain('No active sessions.')
  })

  it('sessions tab: "Revoke all" is gated behind a typed confirm matching the email, and only fires once matched', async () => {
    const fetchMock = stubBaseFetch('ACTIVE', [], { sessions: [sessionFixture] })
    const withRevokeAll = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/users/user-1/sessions' && init?.method === 'DELETE') {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withRevokeAll)

    const router = await createDetailRouter()
    const wrapper = mount(TenantUserDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await wrapper.find('[data-tab="sessions"]').trigger('mousedown')
    await flushPromises()

    await wrapper.find('#revoke-all-sessions-button').trigger('click')
    await flushPromises()

    // The DELETE-all call must not fire before the typed value matches.
    expect(withRevokeAll).not.toHaveBeenCalledWith('/t/acme/api/users/user-1/sessions', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()

    await wrapper.find('#typed-confirm-input').setValue('alice@acme.test')
    await flushPromises()
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeUndefined()

    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(withRevokeAll).toHaveBeenCalledWith('/t/acme/api/users/user-1/sessions', expect.objectContaining({ method: 'DELETE' }))
  })
})
