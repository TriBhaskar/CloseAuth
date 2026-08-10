import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantClientsView from './TenantClientsView.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3c: no fabricated list (the backend has none, and this page must
// say so, not render an empty table implying one exists), no secret input
// anywhere on the register form, and the look-up-by-record-id control
// renders a clear not-found on a 404 rather than behaving like an empty row
// in a list that doesn't exist.
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

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantClientsView', () => {
  it('states plainly that no client list exists — no table, no fabricated rows', async () => {
    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#clients-no-list-notice').exists()).toBe(true)
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('the register form has no secret input at all', async () => {
    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.html()).not.toMatch(/id="[^"]*secret[^"]*"/i)
  })

  it('register: success stores credentials with context "create" and navigates to the handoff view', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
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
                  clientId: 'new-client',
                  clientName: 'New Client',
                  tenantId: 'tenant-1',
                  publicClient: false,
                  grantTypes: ['authorization_code'],
                  scopes: [],
                  redirectUris: ['http://127.0.0.1/callback'],
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
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#new-client-client-id').setValue('new-client')
    await wrapper.find('#new-client-client-name').setValue('New Client')
    await wrapper.find('#new-client-redirect-uris').setValue('http://127.0.0.1/callback')
    await wrapper.find('#client-register-form').trigger('submit.prevent')
    await flushPromises()

    expect(store.credentials?.clientSecret).toBe('generated-secret-abc')
    expect(store.context).toBe('create')
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-credentials')
  })

  it('register: a 400 validation-errors map lands on the specific field, not a generic banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
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

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#new-client-client-name').setValue('New Client')
    await wrapper.find('#client-register-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#new-client-client-id-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('must not be blank')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('look-up: a malformed id is rejected locally, without calling the backend', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-lookup-id').setValue('not-a-uuid')
    await wrapper.find('#client-lookup-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('#client-lookup-error').exists()).toBe(true)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('look-up: a 404 renders a clear not-found message, not an empty row', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/11111111-1111-1111-1111-111111111111') {
          return Promise.resolve({
            ok: false,
            status: 404,
            json: () => Promise.resolve({ error: 'client.not_found', error_description: 'Client not found.' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-lookup-id').setValue('11111111-1111-1111-1111-111111111111')
    await wrapper.find('#client-lookup-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('#client-lookup-error').text()).toContain('No client with that record id exists')
  })

  it('look-up: success navigates straight to the client detail route', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/clients/11111111-1111-1111-1111-111111111111') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                id: '11111111-1111-1111-1111-111111111111',
                clientId: 'found-client',
                clientName: 'Found Client',
                tenantId: 'tenant-1',
                publicClient: false,
                grantTypes: [],
                scopes: [],
                redirectUris: [],
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createClientsRouter()
    const wrapper = mount(TenantClientsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-lookup-id').setValue('11111111-1111-1111-1111-111111111111')
    await wrapper.find('#client-lookup-form').trigger('submit.prevent')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('tenant-admin-client-detail')
    expect(router.currentRoute.value.params.clientId).toBe('11111111-1111-1111-1111-111111111111')
  })
})
