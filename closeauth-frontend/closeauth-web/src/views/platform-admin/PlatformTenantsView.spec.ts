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
})
