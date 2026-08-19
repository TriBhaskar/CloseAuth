import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import CreateClientDialog from './CreateClientDialog.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import { clearCsrfToken } from '@/api/csrf'

const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createTestRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/clients', name: 'tenant-admin-clients', component: { template: '<div />' } },
      {
        path: '/t/:slug/console/clients/credentials',
        name: 'tenant-admin-client-credentials',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/t/acme/console/clients')
  await router.isReady()
  return router
}

function stubEmptyCatalogFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
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
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CreateClientDialog', () => {
  it('step 1: Next is disabled until a type is chosen', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    expect(wrapper.find('#client-wizard-next').attributes('disabled')).toBeDefined()
    await wrapper.find('#client-wizard-type-web').trigger('change')
    expect(wrapper.find('#client-wizard-next').attributes('disabled')).toBeUndefined()
  })

  it('machine-to-machine hides the redirect-URI and post-logout-URI rows in step 2', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-m2m').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')

    expect(wrapper.find('#client-wizard-redirect-0').exists()).toBe(false)
    expect(wrapper.find('#client-wizard-post-logout-0').exists()).toBe(false)
  })

  it('web app: shows redirect-URI rows and rejects a non-https, non-localhost URI', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('my-app')
    await wrapper.find('#client-wizard-client-name').setValue('My App')
    await wrapper.find('#client-wizard-redirect-0').setValue('http://example.com/callback')
    await wrapper.find('#client-wizard-next').trigger('click')

    expect(wrapper.text()).toContain('Must use https, unless the host is localhost.')
  })

  it('web app: accepts a plain http://localhost redirect URI', async () => {
    stubEmptyCatalogFetch()
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('my-app')
    await wrapper.find('#client-wizard-client-name').setValue('My App')
    await wrapper.find('#client-wizard-redirect-0').setValue('http://localhost:5173/callback')
    await wrapper.find('#client-wizard-next').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('rejects a redirect URI containing a fragment', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('my-app')
    await wrapper.find('#client-wizard-client-name').setValue('My App')
    await wrapper.find('#client-wizard-redirect-0').setValue('https://example.com/callback#token')
    await wrapper.find('#client-wizard-next').trigger('click')

    expect(wrapper.text()).toContain('Must not include a fragment.')
  })

  it('add/remove redirect-URI rows', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')

    expect(wrapper.find('#client-wizard-redirect-1').exists()).toBe(false)
    await wrapper.find('#client-wizard-redirect-add').trigger('click')
    expect(wrapper.find('#client-wizard-redirect-1').exists()).toBe(true)
    await wrapper.find('#client-wizard-redirect-remove-1').trigger('click')
    expect(wrapper.find('#client-wizard-redirect-1').exists()).toBe(false)
  })

  it('reaching step 3 loads the scope catalog and renders ScopeSelector', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  {
                    id: 'rs-1',
                    tenantId: 't-1',
                    slug: 'billing-api',
                    name: 'Billing API',
                    audienceIdentifier: 'https://billing.example.com',
                    autoCreated: false,
                    createdAt: '2026-01-01T00:00:00Z',
                    updatedAt: null,
                    scopeCount: 1,
                  },
                ],
                page: 0,
                size: 200,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  {
                    id: 's-1',
                    resourceServerId: 'rs-1',
                    scopeName: 'read',
                    description: null,
                    isDefault: false,
                    requiresConsent: true,
                    createdAt: '2026-01-01T00:00:00Z',
                    usedByRoleCount: 0,
                  },
                ],
                page: 0,
                size: 200,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-m2m').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('m2m-client')
    await wrapper.find('#client-wizard-client-name').setValue('M2M Client')
    await wrapper.find('#client-wizard-next').trigger('click')
    await flushPromises()

    expect(wrapper.find('#scope-selector-billing-api\\:read').exists()).toBe(true)
  })

  it('submitting a machine-to-machine client stores credentials and navigates to the handoff view', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/resource-servers?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () =>
              Promise.resolve({
                client: {
                  id: 'record-1',
                  clientId: 'm2m-client',
                  clientName: 'M2M Client',
                  tenantId: 'tenant-1',
                  publicClient: false,
                  grantTypes: ['client_credentials'],
                  scopes: [],
                  redirectUris: [],
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

    const router = await createTestRouter()
    const store = useTenantAdminClientCredentialsStore()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-m2m').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('m2m-client')
    await wrapper.find('#client-wizard-client-name').setValue('M2M Client')
    await wrapper.find('#client-wizard-next').trigger('click')
    await flushPromises()
    await wrapper.find('#client-wizard-submit').trigger('click')
    await flushPromises()

    expect(store.credentials?.clientSecret).toBe('generated-secret-abc')
    expect(store.context).toBe('create')
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-credentials')
  })

  it('a 400 validation-errors map lands the field error and returns to step 2', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/resource-servers?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/clients' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({
                error: 'validation.failed',
                error_description: 'Request validation failed',
                errors: { clientId: 'must not be blank' },
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-m2m').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    await wrapper.find('#client-wizard-client-id').setValue('m2m-client')
    await wrapper.find('#client-wizard-client-name').setValue('M2M Client')
    await wrapper.find('#client-wizard-next').trigger('click')
    await flushPromises()
    await wrapper.find('#client-wizard-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('#client-wizard-client-id-error').text()).toBe('must not be blank')
  })

  it('reopening resets the wizard back to step 1', async () => {
    const router = await createTestRouter()
    const wrapper = mount(CreateClientDialog, {
      props: { open: true, slug: 'acme' },
      global: { plugins: [router], stubs: dialogStubs },
    })

    await wrapper.find('#client-wizard-type-web').trigger('change')
    await wrapper.find('#client-wizard-next').trigger('click')
    expect(wrapper.find('#client-wizard-client-id').exists()).toBe(true)

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })

    expect(wrapper.find('#client-wizard-client-id').exists()).toBe(false)
    expect(wrapper.find('#client-wizard-type-web').exists()).toBe(true)
  })
})
