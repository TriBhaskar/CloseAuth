import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantClientDetailView from './TenantClientDetailView.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import { clearCsrfToken } from '@/api/csrf'

// FE-4c: the detail view's required proofs. Now tabbed (Configuration ·
// Credentials · Branding) — reka-ui's TabsTrigger generates its own id
// (overriding any id passed at the call site, hence data-tab as the test
// hook) and switches on @mousedown.left, not @click (FE-4a's own
// established gotcha, applies here too). Rotate is gated behind
// TypedConfirmDialog (match text = client_id), no rotate control renders
// for a public client, and a successful rotate stashes the new credentials
// and navigates to the handoff view. Branding renders an honest placeholder,
// never a broken form, per the tracked-gap decision.
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
    postLogoutRedirectUris: [],
    createdAt: '2026-01-01T00:00:00Z',
    secretRotatedAt: null,
    ...overrides,
  }
}

async function switchTab(wrapper: ReturnType<typeof mount>, tab: string): Promise<void> {
  await wrapper.find(`[data-tab="${tab}"]`).trigger('mousedown')
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantClientDetailView', () => {
  it('renders all three tabs', async () => {
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

    expect(wrapper.find('[data-tab="configuration"]').exists()).toBe(true)
    expect(wrapper.find('[data-tab="credentials"]').exists()).toBe(true)
    expect(wrapper.find('[data-tab="branding"]').exists()).toBe(true)
  })

  it('Configuration tab shows the client_id chip and post-logout URIs', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(clientFixture({ postLogoutRedirectUris: ['https://app.example.com/logged-out'] })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('billing-api')
    expect(wrapper.text()).toContain('https://app.example.com/logged-out')
  })

  it('Credentials tab shows "Never rotated" before any rotation', async () => {
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
    await switchTab(wrapper, 'credentials')

    expect(wrapper.text()).toContain('Never rotated')
  })

  it('Credentials tab shows a relative rotation time once secretRotatedAt is present', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(clientFixture({ secretRotatedAt: '2026-01-02T00:00:00Z' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'credentials')

    expect(wrapper.text()).not.toContain('Never rotated')
  })

  it('confidential client: offers a rotate control', async () => {
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
    await switchTab(wrapper, 'credentials')

    expect(wrapper.find('#client-regenerate-secret').exists()).toBe(true)
  })

  it('public client: NO rotate control renders — never offer what the backend would refuse', async () => {
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
    await switchTab(wrapper, 'credentials')

    expect(wrapper.find('#client-regenerate-secret').exists()).toBe(false)
    expect(wrapper.text()).toContain('has no secret, so there is nothing to regenerate')
  })

  it('rotate is gated behind TypedConfirmDialog whose copy names the invalidation consequence, and the confirm button stays disabled until the client_id is typed exactly', async () => {
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
    await switchTab(wrapper, 'credentials')

    await wrapper.find('#client-regenerate-secret').trigger('click')
    expect(wrapper.text()).toContain('stops working immediately')
    expect(wrapper.text()).toContain('Already-issued access tokens keep working until they expire')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()

    await wrapper.find('#typed-confirm-input').setValue('billing-api')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeUndefined()
  })

  it('confirming rotate stashes the new credentials and navigates to the handoff view', async () => {
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
            json: () =>
              Promise.resolve({
                client: clientFixture({ secretRotatedAt: '2026-01-02T00:00:00Z' }),
                clientSecret: 'freshly-generated-secret-value',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const store = useTenantAdminClientCredentialsStore()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'credentials')

    await wrapper.find('#client-regenerate-secret').trigger('click')
    await wrapper.find('#typed-confirm-input').setValue('billing-api')
    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
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
    await switchTab(wrapper, 'credentials')

    await wrapper.find('#client-regenerate-secret').trigger('click')
    await wrapper.find('#typed-confirm-input').setValue('billing-api')
    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('This is a public client — it has no secret to regenerate.')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })

  it('Branding tab renders an honest placeholder naming the gap, not a broken form', async () => {
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
    await switchTab(wrapper, 'branding')

    expect(wrapper.text()).toContain("isn't available yet")
    expect(wrapper.find('form').exists()).toBe(false)
  })
})
