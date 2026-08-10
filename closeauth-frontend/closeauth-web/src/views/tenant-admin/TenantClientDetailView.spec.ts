import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantClientDetailView from './TenantClientDetailView.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3c: the detail view's required proofs — regenerate is gated
// behind ConfirmDialog with copy naming the real invalidation consequence,
// no regenerate control renders for a public client, and a successful
// regenerate stashes the new credentials and navigates to the handoff view.
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
      { path: '/t/:slug/console/clients', name: 'tenant-admin-clients', component: { template: '<div />' } },
      {
        path: '/t/:slug/console/clients/credentials',
        name: 'tenant-admin-client-credentials',
        component: { template: '<div />' },
      },
      {
        path: '/t/:slug/console/clients/:clientId',
        name: 'tenant-admin-client-detail',
        component: TenantClientDetailView,
      },
    ],
  })
  await router.push('/t/acme/console/clients/record-1')
  await router.isReady()
  return router
}

function clientFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'record-1',
    clientId: 'billing-api',
    clientName: 'Billing API',
    tenantId: 'tenant-1',
    publicClient: false,
    grantTypes: ['client_credentials'],
    scopes: [],
    redirectUris: [],
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

describe('TenantClientDetailView', () => {
  it('confidential client: offers a regenerate control', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#client-regenerate-secret').exists()).toBe(true)
  })

  it('public client: NO regenerate control renders — never offer what the backend would refuse', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(clientFixture({ publicClient: true })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#client-regenerate-secret').exists()).toBe(false)
    expect(wrapper.text()).toContain('has no secret, so there is nothing to regenerate')
  })

  it('regenerate is gated behind ConfirmDialog whose copy names the invalidation consequence', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    // Clicking the trigger does not itself call the regenerate endpoint —
    // only opens the confirmation.
    await wrapper.find('#client-regenerate-secret').trigger('click')
    expect(wrapper.text()).toContain('stops working immediately')
    expect(wrapper.text()).toContain('Already-issued access tokens keep working until they expire')
  })

  it('confirming regenerate stashes the new credentials and navigates to the handoff view', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1/client-secret' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ client: clientFixture(), clientSecret: 'freshly-generated-secret-value' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const store = useTenantAdminClientCredentialsStore()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#client-regenerate-secret').trigger('click')
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(store.credentials?.clientSecret).toBe('freshly-generated-secret-value')
    expect(store.context).toBe('regenerate')
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-credentials')
  })

  it('409 client.public_no_secret renders the SPECIFIC message, not a generic conflict banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1/client-secret' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({ error: 'client.public_no_secret', error_description: 'Client is public.' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#client-regenerate-secret').trigger('click')
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('This is a public client — it has no secret to regenerate.')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })
})
