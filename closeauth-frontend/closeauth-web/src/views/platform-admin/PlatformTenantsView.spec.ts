import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformTenantsView from './PlatformTenantsView.vue'
import { clearCsrfToken } from '@/api/csrf'
import { useToast } from '@/composables/useToast'

// Stage UI-4 / FE-3a: the tenants surface's required proofs — row actions
// offered per status match availableTenantActions(status) exactly (notably:
// no suspend button on a PROVISIONING row, no actions at all on a DELETED
// row), suspend/activate/delete are all confirmed (spec §6.3.5 — delete via
// TypedConfirmDialog's exact-Tenant-ID gate), a successful provision names
// everything the backend auto-created, and client-side search (FE-3a's
// confirmed decision — no server-side tenant search exists end-to-end yet)
// filters by name and Tenant ID.
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
    routes: [
      { path: '/platform/console/tenants', name: 'platform-admin-tenants', component: PlatformTenantsView },
      { path: '/platform/console/tenants/:tenantId', name: 'platform-admin-tenant-detail', component: { template: '<div id="detail-stub" />' } },
    ],
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
  // useToast's `toasts` list is module-level shared state — clear it so a
  // toast fired by one test never leaks into the next one's assertions.
  useToast().toasts.value.splice(0)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PlatformTenantsView', () => {
  it('clicking a row navigates to its detail page, but clicking a lifecycle button inside the row does not', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [tenantFixture({ id: 't-1', slug: 'acme', status: 'ACTIVE' })],
                page: 0, size: 200, totalElements: 1, totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    // Clicking the row's Suspend button must NOT also navigate.
    await wrapper.find('#tenant-action-t-1-suspend').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('platform-admin-tenants')

    await wrapper.find('[data-tenant-id="t-1"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('platform-admin-tenant-detail')
    expect(router.currentRoute.value.params.tenantId).toBe('t-1')
  })

  it('renders tenants and offers actions strictly from the real transition matrix', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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

  it('search filters the loaded list client-side by name and by Tenant ID (FE-3a: no server-side tenant search exists yet)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  tenantFixture({ id: 't-acme', slug: 'ten_acme-inc', name: 'Acme Inc' }),
                  tenantFixture({ id: 't-globex', slug: 'ten_globex-corp', name: 'Globex Corp' }),
                ],
                page: 0,
                size: 200,
                totalElements: 2,
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

    expect(wrapper.find('[data-tenant-id="t-acme"]').exists()).toBe(true)
    expect(wrapper.find('[data-tenant-id="t-globex"]').exists()).toBe(true)

    await wrapper.find('input[type="search"]').setValue('globex')
    await new Promise((resolve) => setTimeout(resolve, 350)) // DataTable's own 300ms debounce
    await flushPromises()

    expect(wrapper.find('[data-tenant-id="t-globex"]').exists()).toBe(true)
    expect(wrapper.find('[data-tenant-id="t-acme"]').exists()).toBe(false)

    // Also matches by Tenant ID, not just name.
    await wrapper.find('input[type="search"]').setValue('ten_acme-inc')
    await new Promise((resolve) => setTimeout(resolve, 350))
    await flushPromises()

    expect(wrapper.find('[data-tenant-id="t-acme"]').exists()).toBe(true)
    expect(wrapper.find('[data-tenant-id="t-globex"]').exists()).toBe(false)
  })

  it('suspend confirm copy is spec-literal and names the tenant, and now requires confirmation for activate too', async () => {
    // A stateful list handler — suspend actually flips the fixture's status,
    // so the list re-fetch that runLifecycle triggers after a successful
    // action reflects the real new state, not a canned one.
    let status = 'ACTIVE'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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

    expect(wrapper.text()).toContain('Suspend Acme?')
    expect(wrapper.text()).toContain("Users won't be able to sign in and all active tokens stop working immediately.")

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-tenant-id="tenant-1"]').text()).toContain('SUSPENDED')
  })

  it('activate now requires confirmation (previously fired immediately)', async () => {
    let status = 'SUSPENDED'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [tenantFixture({ status })], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1/activate' && init?.method === 'POST') {
          status = 'ACTIVE'
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(tenantFixture({ status })) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-action-tenant-1-activate').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Activate Acme?')
    expect(wrapper.find('[data-tenant-id="tenant-1"]').text()).toContain('SUSPENDED') // not yet applied

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-tenant-id="tenant-1"]').text()).toContain('ACTIVE')
  })

  it('delete requires the exact Tenant ID typed before the confirm button is enabled', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [tenantFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/tenants/tenant-1' && init?.method === 'DELETE') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(tenantFixture({ status: 'DELETED' })) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-action-tenant-1-delete').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Delete Acme?')
    const confirmButton = wrapper.find('#typed-confirm-dialog-confirm')
    expect(confirmButton.attributes('disabled')).toBeDefined()

    await wrapper.find('#typed-confirm-input').setValue('acme')
    await flushPromises()

    expect(confirmButton.attributes('disabled')).toBeUndefined()
  })

  it('the Tenant ID preview updates live as the operator types, and falls back to a generic line for a reserved/empty name', async () => {
    const router = await createTenantsRouter()
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-tenant-name').setValue('Acmé Inc.')
    await flushPromises()
    expect(wrapper.find('#new-tenant-id-preview').text()).toContain('ten_acme-inc')

    await wrapper.find('#new-tenant-name').setValue('Admin')
    await flushPromises()
    expect(wrapper.find('#new-tenant-id-preview').text()).toBe('Tenant ID will be generated automatically.')
  })

  it('provision success fires a "Tenant provisioned" toast, then auto-activates straight into the bootstrap dialog — no intermediate consent step', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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
        if (url === '/platform/api/tenants/t-new/activate' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(tenantFixture({ id: 't-new', slug: 'newco', name: 'Newco', status: 'ACTIVE' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-tenant-name').setValue('Newco')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // No 3-way "activate?" choice anywhere — the old ids are gone entirely.
    expect(wrapper.find('#onboarding-activate-and-bootstrap').exists()).toBe(false)
    expect(wrapper.find('#onboarding-later').exists()).toBe(false)

    // The toast fired (checked on the shared list — no <Toaster/> is mounted
    // in this isolated view test, see App.vue for where it actually renders).
    const { toasts } = useToast()
    expect(toasts.value.some((t) => t.title === 'Tenant provisioned')).toBe(true)

    // Straight into the bootstrap dialog, naming what was auto-created.
    expect(wrapper.text()).toContain("Add newco's first admin")
    expect(wrapper.text()).toContain('admin-console-newco')
    expect(wrapper.find('#bootstrap-admin-form').exists()).toBe(true)
  })

  it('if the auto-activate call itself fails, shows a retry recovery dialog rather than a raw error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/platform/api/tenants' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true, status: 201,
            json: () => Promise.resolve(tenantFixture({ id: 't-new', slug: 'newco', name: 'Newco', status: 'PROVISIONING' })),
          })
        }
        if (url === '/platform/api/tenants/t-new/activate' && init?.method === 'POST') {
          return Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({ error: 'server_error', error_description: 'boom' }) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTenantsRouter()
    const wrapper = mount(PlatformTenantsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-tenant-name').setValue('Newco')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain("Couldn't activate Newco")
    expect(wrapper.find('#onboarding-activate-retry').exists()).toBe(true)
    expect(wrapper.find('#bootstrap-admin-form').exists()).toBe(false)
  })

  // ---- Stage UI-4b: tenant onboarding ------------------------------------

  it('flags a zero-admin ACTIVE tenant and offers the onboarding action the adminCount actually supports', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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

    // Zero-admin ACTIVE tenant: flagged "Incomplete" with the spec tooltip, offers "Add first admin" only.
    const zeroRow = wrapper.find('[data-tenant-id="t-zero"]')
    expect(zeroRow.text()).toContain('Incomplete')
    const incompleteBadge = zeroRow.findAll('*').find((el) => el.text() === 'Incomplete')
    expect(incompleteBadge?.attributes('title')).toBe('No tenant admin has been created yet.')
    expect(wrapper.find('#tenant-bootstrap-t-zero').exists()).toBe(true)
    expect(wrapper.find('#tenant-reissue-t-zero').exists()).toBe(false)

    // ACTIVE tenant with admins: not flagged, offers "Reissue credential" only.
    expect(wrapper.find('[data-tenant-id="t-has"]').text()).not.toContain('Incomplete')
    expect(wrapper.find('#tenant-reissue-t-has').exists()).toBe(true)
    expect(wrapper.find('#tenant-bootstrap-t-has').exists()).toBe(false)

    // adminCount null ("not computed"): never fabricate a flag or an action.
    expect(wrapper.find('[data-tenant-id="t-unknown"]').text()).not.toContain('Incomplete')
    expect(wrapper.find('#tenant-bootstrap-t-unknown').exists()).toBe(false)
    expect(wrapper.find('#tenant-reissue-t-unknown').exists()).toBe(false)

    // Non-ACTIVE: neither action, regardless of adminCount.
    expect(wrapper.find('#tenant-bootstrap-t-prov').exists()).toBe(false)
    expect(wrapper.find('#tenant-reissue-t-prov').exists()).toBe(false)
  })

  it('keeps the one-time temporary password out of the DOM until it is deliberately revealed, and gates Continue behind acknowledgement', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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

    // The emailed link leads; the fallback password never renders until it's
    // deliberately revealed — the load-bearing §2.2.4 assertion. The bootstrap
    // dialog itself is gone (replaced by the standalone SecretRevealPanel).
    expect(wrapper.find('#bootstrap-admin-form').exists()).toBe(false)
    expect(wrapper.text()).toContain('An onboarding link was already emailed')
    expect(wrapper.text()).not.toContain('super-secret-one-time-value')
    const continueButton = wrapper.find('#secret-reveal-continue')
    expect(continueButton.attributes('disabled')).toBeDefined()

    // Masked by default — the temp-password field, not the (deliberately
    // plain) Tenant ID / sign-in URL fields.
    expect(wrapper.find('#secret-reveal-temp-password').text()).not.toContain('super-secret-one-time-value')
    expect(wrapper.find('#secret-reveal-temp-password').text()).toMatch(/^•+$/)
    expect(wrapper.find('#secret-reveal-tenant-id').text()).toBe('acme')

    const revealButton = wrapper.findAll('button').find((b) => b.text() === 'Reveal')
    await revealButton!.trigger('click')
    await flushPromises()
    expect(wrapper.find('#secret-reveal-temp-password').text()).toBe('super-secret-one-time-value')

    await wrapper.find('#secret-reveal-ack').trigger('click')
    await flushPromises()
    expect(continueButton.attributes('disabled')).toBeUndefined()
  })

  it('409 tenant_onboarding.admin_already_exists renders its own message on bootstrap, not a generic conflict banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
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
        if (url === '/platform/api/tenants?page=0&size=200') {
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
      if (url === '/platform/api/tenants?page=0&size=200') {
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
