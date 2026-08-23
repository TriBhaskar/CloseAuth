import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantClientsView from './TenantClientsView.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import { clearCsrfToken } from '@/api/csrf'

// FE-4.10: rebuilt onto DataTable now that TenantClientController exposes a
// real tenant-scoped list — the record-id lookup control is gone, replaced
// by rows that link straight to the detail view. Still proven here: no
// secret input anywhere reachable from this page, register opens the
// three-step CreateClientDialog wizard (its own dedicated spec covers the
// wizard's internal behavior in full — these tests only prove the view
// wires it correctly).
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createClientsRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/clients', name: 'tenant-admin-clients', component: TenantClientsView },
      {
        path: '/t/:slug/console/clients/credentials',
        name: 'tenant-admin-client-credentials',
        component: { template: '<div />' },
      },
      {
        path: '/t/:slug/console/clients/:clientId',
        name: 'tenant-admin-client-detail',
        component: { template: '<div />' },
      },
      {
        path: '/t/:slug/console/resource-servers',
        name: 'tenant-admin-resource-servers',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/t/acme/console/clients')
  await router.isReady()
  return router
}

function clientFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'client-1',
    clientId: 'todomaster-spa-abc123',
    clientName: 'TodoMaster SPA',
    tenantId: 'tenant-1',
    publicClient: false,
    grantTypes: ['authorization_code', 'refresh_token'],
    scopes: [],
    redirectUris: ['http://127.0.0.1/callback'],
    postLogoutRedirectUris: [],
    createdAt: '2026-01-01T00:00:00Z',
    secretRotatedAt: null,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantClientsView', () => {
  it('renders clients from a real PageView, with a Type badge, no alert on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [clientFixture(), clientFixture({ id: 'client-2', clientName: 'Public SPA', publicClient: true })],
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

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const confidentialRow = wrapper.find('[data-client-id="client-1"]')
    expect(confidentialRow.text()).toContain('Confidential')
    expect(confidentialRow.text()).toContain('TodoMaster SPA')
    const publicRow = wrapper.find('[data-client-id="client-2"]')
    expect(publicRow.text()).toContain('Public')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows a visible error and renders NO rows when the list fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 502,
          json: () => Promise.resolve({ error: 'bad_gateway', error_description: 'Could not reach the backend.' }),
        }),
      ),
    )

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Could not reach the backend.')
    expect(wrapper.findAll('[data-client-id]').length).toBe(0)
  })

  it('search filters client-side by name or client id', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [clientFixture(), clientFixture({ id: 'client-2', clientName: 'Other App', clientId: 'other-app-xyz' })],
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

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('input[type="search"]').setValue('todomaster')
    await new Promise((resolve) => setTimeout(resolve, 350)) // DataTable debounces search 300ms
    await flushPromises()

    expect(wrapper.find('[data-client-id="client-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-client-id="client-2"]').exists()).toBe(false)
  })

  it('clicking a row navigates straight to the client detail route', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [clientFixture()], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('[data-client-id="client-1"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('tenant-admin-client-detail')
    expect(router.currentRoute.value.params.clientId).toBe('client-1')
  })

  it('opening the register wizard has no secret input anywhere', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#open-create-client-wizard').trigger('click')

    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.html()).not.toMatch(/id="[^"]*secret[^"]*"/i)
  })

  it('register: success (via the wizard) stores credentials with context "create" and navigates to the handoff view', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/t/acme/api/clients' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () =>
              Promise.resolve({
                client: {
                  id: 'record-1',
                  clientId: 'new-client',
                  clientName: 'New Client',
                  tenantId: 'tenant-1',
                  publicClient: false,
                  grantTypes: ['authorization_code', 'refresh_token'],
                  scopes: [],
                  redirectUris: ['http://127.0.0.1/callback'],
                  postLogoutRedirectUris: [],
                  createdAt: '2026-01-01T00:00:00Z',
                  secretRotatedAt: null,
                },
                clientSecret: 'generated-secret-abc',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createClientsRouter()
    const store = useTenantAdminClientCredentialsStore()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#open-create-client-wizard').trigger('click')
    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-name').setValue('New Client')
    await wrapper.find('#client-wizard-redirect-0').setValue('http://127.0.0.1/callback')
    await wrapper.find('#client-wizard-next').trigger('click')
    await flushPromises()
    await wrapper.find('#client-wizard-submit').trigger('click')
    await flushPromises()

    expect(store.credentials?.clientSecret).toBe('generated-secret-abc')
    expect(store.context).toBe('create')
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-credentials')
  })
})
