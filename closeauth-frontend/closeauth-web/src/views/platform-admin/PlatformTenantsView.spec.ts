import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformTenantsView from './PlatformTenantsView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-4: the tenants surface's required proofs — row actions offered
// per status match availableTenantActions(status) exactly (notably: no
// suspend button on a PROVISIONING row, no actions at all on a DELETED row),
// the suspend confirm copy names the real immediate session/token
// revocation consequence, and a successful provision names everything the
// backend auto-created.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createTenantsRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/platform/console/tenants', name: 'platform-admin-tenants', component: PlatformTenantsView }],
  })
  await router.push('/platform/console/tenants')
  await router.isReady()
  return router
}

function tenantFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'tenant-1',
    slug: 'acme',
    name: 'Acme',
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    adminCount: 0,
    ...overrides,
  }
}

function tenantUserFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'u-1',
    tenantId: 'tenant-1',
    email: 'ada@acme.test',
    emailVerified: true,
    phone: null,
    phoneVerified: false,
    firstName: 'Ada',
    lastName: 'Admin',
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PlatformTenantsView', () => {
  it('renders tenants and offers actions strictly from the real transition matrix', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  tenantFixture({ id: 't-prov', slug: 'prov', status: 'PROVISIONING' }),
                  tenantFixture({ id: 't-active', slug: 'active', status: 'ACTIVE' }),
                  tenantFixture({ id: 't-susp', slug: 'susp', status: 'SUSPENDED' }),
                  tenantFixture({ id: 't-deleted', slug: 'deleted', status: 'DELETED' }),
                ],
                page: 0,
                size: 20,
                totalElements: 4,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    // PROVISIONING: activate + delete, no suspend.
    expect(wrapper.find('#tenant-action-t-prov-activate').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-prov-delete').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-prov-suspend').exists()).toBe(false)

    // ACTIVE: suspend + delete, no activate.
    expect(wrapper.find('#tenant-action-t-active-suspend').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-active-delete').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-active-activate').exists()).toBe(false)

    // SUSPENDED: activate + delete, no suspend.
    expect(wrapper.find('#tenant-action-t-susp-activate').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-susp-delete').exists()).toBe(true)
    expect(wrapper.find('#tenant-action-t-susp-suspend').exists()).toBe(false)

    // DELETED: terminal, no actions at all.
    expect(wrapper.find('#tenant-action-t-deleted-activate').exists()).toBe(false)
    expect(wrapper.find('#tenant-action-t-deleted-suspend').exists()).toBe(false)
    expect(wrapper.find('#tenant-action-t-deleted-delete').exists()).toBe(false)
  })

  it('suspend confirm copy names the immediate session/token revocation consequence', async () => {
    // A stateful list handler — suspend actually flips the fixture's status,
    // so the list re-fetch that runLifecycle triggers after a successful
    // action reflects the real new state, not a canned one.
    let status = 'ACTIVE'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [tenantFixture({ status })], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/suspend' && init?.method === 'POST') {
          status = 'SUSPENDED'
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(tenantFixture({ status })) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-action-tenant-1-suspend').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('revokes every user')
    expect(wrapper.text()).toContain('signed out now')

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-tenant-id="tenant-1"]').text()).toContain('SUSPENDED')
  })

  it('provision success names the auto-created starter-pack, branding, registration config, and admin-console client', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/platform/api/tenants' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () => Promise.resolve(tenantFixture({ id: 't-new', slug: 'newco', name: 'Newco', status: 'PROVISIONING' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-tenant-slug').setValue('newco')
    await wrapper.find('#new-tenant-name').setValue('Newco')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const banner = wrapper.find('[role="status"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('starter-pack roles and scopes')
    expect(banner.text()).toContain('admin-console-newco')
    expect(banner.text()).toContain('PROVISIONING')
  })

  // ---- Stage UI-4b: tenant onboarding ------------------------------------

  it('flags a zero-admin ACTIVE tenant and offers the onboarding action the adminCount actually supports', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  tenantFixture({ id: 't-zero', slug: 'zero', status: 'ACTIVE', adminCount: 0 }),
                  tenantFixture({ id: 't-has', slug: 'has', status: 'ACTIVE', adminCount: 2 }),
                  tenantFixture({ id: 't-unknown', slug: 'unknown', status: 'ACTIVE', adminCount: null }),
                  tenantFixture({ id: 't-prov', slug: 'prov', status: 'PROVISIONING', adminCount: 0 }),
                ],
                page: 0,
                size: 20,
                totalElements: 4,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    // Zero-admin ACTIVE tenant: flagged, offers "Add first admin" only.
    expect(wrapper.find('[data-tenant-id="t-zero"]').text()).toContain('No admin')
    expect(wrapper.find('#tenant-bootstrap-t-zero').exists()).toBe(true)
    expect(wrapper.find('#tenant-reissue-t-zero').exists()).toBe(false)

    // ACTIVE tenant with admins: not flagged, offers "Reissue credential" only.
    expect(wrapper.find('[data-tenant-id="t-has"]').text()).not.toContain('No admin')
    expect(wrapper.find('#tenant-reissue-t-has').exists()).toBe(true)
    expect(wrapper.find('#tenant-bootstrap-t-has').exists()).toBe(false)

    // adminCount null ("not computed"): never fabricate a flag or an action.
    expect(wrapper.find('[data-tenant-id="t-unknown"]').text()).not.toContain('No admin')
    expect(wrapper.find('#tenant-bootstrap-t-unknown').exists()).toBe(false)
    expect(wrapper.find('#tenant-reissue-t-unknown').exists()).toBe(false)

    // Non-ACTIVE: neither action, regardless of adminCount.
    expect(wrapper.find('#tenant-bootstrap-t-prov').exists()).toBe(false)
    expect(wrapper.find('#tenant-reissue-t-prov').exists()).toBe(false)
  })

  it('post-provision leads into a guided prompt naming the activation requirement', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/platform/api/tenants' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () => Promise.resolve(tenantFixture({ id: 't-new', slug: 'newco', name: 'Newco', status: 'PROVISIONING' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-tenant-slug').setValue('newco')
    await wrapper.find('#new-tenant-name').setValue('Newco')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('must be ACTIVE before it can have an admin')
    expect(wrapper.find('#onboarding-activate-and-bootstrap').exists()).toBe(true)
    expect(wrapper.find('#onboarding-later').exists()).toBe(true)
  })

  it('keeps the one-time temporary password out of the DOM until the fallback disclosure is opened, and gates Done behind acknowledgement', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [tenantFixture({ status: 'ACTIVE', adminCount: 0 })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/bootstrap-admin' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () =>
              Promise.resolve({
                user: tenantUserFixture({ email: 'ada@acme.test' }),
                temporaryPassword: 'super-secret-one-time-value',
                temporaryPasswordExpiresAt: '2026-01-08T00:00:00Z',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-bootstrap-tenant-1').trigger('click')
    await flushPromises()
    await wrapper.find('#bootstrap-admin-email').setValue('ada@acme.test')
    await wrapper.find('#bootstrap-admin-confirm-email').setValue('ada@acme.test')
    await wrapper.find('#bootstrap-admin-form').trigger('submit.prevent')
    await flushPromises()

    // The emailed link leads; the fallback password never renders until the
    // disclosure is deliberately opened — the load-bearing §2.2.4 assertion.
    expect(wrapper.text()).toContain('An onboarding link was emailed')
    expect(wrapper.text()).not.toContain('super-secret-one-time-value')
    expect(wrapper.find('#onboarding-done').attributes('disabled')).toBeDefined()

    await wrapper.find('#onboarding-disclosure-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.find('#onboarding-password').text()).not.toContain('super-secret-one-time-value')
    expect(wrapper.find('#onboarding-password').text()).toMatch(/^•+$/)

    await wrapper.find('#onboarding-password-reveal').trigger('click')
    await flushPromises()
    expect(wrapper.find('#onboarding-password').text()).toBe('super-secret-one-time-value')

    await wrapper.find('#onboarding-ack').trigger('click')
    await flushPromises()
    expect(wrapper.find('#onboarding-done').attributes('disabled')).toBeUndefined()
  })

  it('409 tenant_onboarding.admin_already_exists renders its own message on bootstrap, not a generic conflict banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [tenantFixture({ status: 'ACTIVE', adminCount: 0 })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/bootstrap-admin' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({
                error: 'tenant_onboarding.admin_already_exists',
                error_description: 'Tenant tenant-1 already has an active TENANT_ADMIN',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-bootstrap-tenant-1').trigger('click')
    await flushPromises()
    await wrapper.find('#bootstrap-admin-email').setValue('ada@acme.test')
    await wrapper.find('#bootstrap-admin-confirm-email').setValue('ada@acme.test')
    await wrapper.find('#bootstrap-admin-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('Use "Reissue credential"')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })

  it('409 tenant_onboarding.no_pending_temp_credential renders its own message on reissue, not a generic conflict banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [tenantFixture({ status: 'ACTIVE', adminCount: 1 })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/users?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [tenantUserFixture()], page: 0, size: 100, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/users/u-1/reissue-onboarding-credential' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({
                error: 'tenant_onboarding.no_pending_temp_credential',
                error_description: 'User u-1 has no pending temporary credential to reissue',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-reissue-tenant-1').trigger('click')
    await flushPromises()
    await wrapper.find('#reissue-select-u-1').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('already set their own password')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })

  it('a mismatched confirm-email blocks bootstrap submission without a network call', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === '/platform/api/tenants?page=0&size=20') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              items: [tenantFixture({ status: 'ACTIVE', adminCount: 0 })],
              page: 0,
              size: 20,
              totalElements: 1,
              totalPages: 1,
            }),
        })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-bootstrap-tenant-1').trigger('click')
    await flushPromises()
    await wrapper.find('#bootstrap-admin-email').setValue('ada@acme.test')
    await wrapper.find('#bootstrap-admin-confirm-email').setValue('ada+typo@acme.test')

    const callsBefore = fetchMock.mock.calls.length
    await wrapper.find('#bootstrap-admin-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    expect(wrapper.text()).toContain('does not match the email above')
  })
})
