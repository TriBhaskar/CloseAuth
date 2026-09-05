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
    requireProofKey: false,
    trusted: true,
    platformManaged: false,
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
            // Client update/delete: post-logout URIs are only rendered (in the
            // edit form's UriListField) when the client's grantTypes include
            // authorization_code — RegisterClientCommand's javadoc: "ignored
            // otherwise."
            json: () =>
              Promise.resolve(
                clientFixture({
                  grantTypes: ['authorization_code'],
                  postLogoutRedirectUris: ['https://app.example.com/logged-out'],
                }),
              ),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('billing-api')
    // The URI lives in an <input>'s value property, not text content — assert
    // on the element itself, same as UriListField.spec.ts does elsewhere.
    const postLogoutInput = wrapper.find('#client-edit-post-logout-0').element as HTMLInputElement
    expect(postLogoutInput.value).toBe('https://app.example.com/logged-out')
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

  // ---- Client update/delete -----------------------------------------

  function stubEmptyCatalog(url: string): { ok: boolean; status: number; json: () => Promise<unknown> } | null {
    if (url === '/t/acme/api/resource-servers?page=0&size=200') {
      return {
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
      }
    }
    return null
  }

  it('a normal client offers the edit form; saving PATCHes and re-seeds from the response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const catalogStub = stubEmptyCatalog(url)
        if (catalogStub) return Promise.resolve(catalogStub)
        if (url === '/t/acme/api/clients/record-1' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1' && init?.method === 'PATCH') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(clientFixture({ clientName: 'Renamed Client' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#client-edit-form').exists()).toBe(true)
    await wrapper.find('#client-edit-name').setValue('Renamed Client')
    await wrapper.find('#client-edit-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#client-detail-name').text()).toBe('Renamed Client')
  })

  it('a 400 validation-errors response lands the field error on the edit form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const catalogStub = stubEmptyCatalog(url)
        if (catalogStub) return Promise.resolve(catalogStub)
        if (url === '/t/acme/api/clients/record-1' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1' && init?.method === 'PATCH') {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({
                error: 'validation.failed',
                error_description: 'Request validation failed',
                errors: { clientName: 'must not be blank' },
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#client-edit-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#client-edit-name-error').text()).toBe('must not be blank')
  })

  it('the platform-managed admin-console client hides both the edit form and the delete button', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/record-1') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve(
                clientFixture({ clientId: 'admin-console-acme', publicClient: true, platformManaged: true }),
              ),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#client-edit-form').exists()).toBe(false)
    expect(wrapper.text()).toContain('platform-managed and cannot be edited')
    expect(wrapper.find('#client-delete-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('platform-managed and cannot be deleted')
  })

  it('delete is gated behind TypedConfirmDialog; a successful delete navigates back to the client list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const catalogStub = stubEmptyCatalog(url)
        if (catalogStub) return Promise.resolve(catalogStub)
        if (url === '/t/acme/api/clients/record-1' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1' && init?.method === 'DELETE') {
          return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#client-delete-button').trigger('click')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()

    await wrapper.find('#typed-confirm-input').setValue('billing-api')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeUndefined()
    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('tenant-admin-clients')
  })

  it('a 409 on delete surfaces the message without navigating away', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const catalogStub = stubEmptyCatalog(url)
        if (catalogStub) return Promise.resolve(catalogStub)
        if (url === '/t/acme/api/clients/record-1' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(clientFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients/record-1' && init?.method === 'DELETE') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({ error: 'client.platform_managed', error_description: 'Client is platform-managed.' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantClientDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#client-delete-button').trigger('click')
    await wrapper.find('#typed-confirm-input').setValue('billing-api')
    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Client is platform-managed.')
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-detail')
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
