import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantResourceServersView from './TenantResourceServersView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3c: list + paging + create (the same pattern TenantUsersView.vue
// established), plus this surface's own required proof — a slug conflict on
// create lands on the slug field specifically (via
// RESOURCE_SERVER_CONFLICT_FIELDS), never a generic banner, with exactly one
// role="alert" in the DOM (no duplicate field+banner for the same error).
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createRSRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/resource-servers', name: 'tenant-admin-resource-servers', component: TenantResourceServersView },
      {
        path: '/t/:slug/console/resource-servers/:rsId',
        name: 'tenant-admin-resource-server-detail',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/t/acme/console/resource-servers')
  await router.isReady()
  return router
}

function rsFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'rs-1',
    tenantId: 'tenant-1',
    slug: 'billing-api',
    name: 'Billing API',
    audienceIdentifier: 'https://acme.rs.closeauth.io/billing-api',
    autoCreated: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: null,
    ...overrides,
  }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantResourceServersView', () => {
  it('renders resource servers from a real PageView, with a Source badge, no alert on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [rsFixture(), rsFixture({ id: 'rs-2', slug: 'auto-app', autoCreated: true })],
                page: 0,
                size: 20,
                totalElements: 2,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createRSRouter()
    const wrapper = mount(TenantResourceServersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const standaloneRow = wrapper.find('[data-resource-server-id="rs-1"]')
    expect(standaloneRow.text()).toContain('Standalone')
    const autoRow = wrapper.find('[data-resource-server-id="rs-2"]')
    expect(autoRow.text()).toContain('Created with a client')
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

    const router = await createRSRouter()
    const wrapper = mount(TenantResourceServersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Could not reach the backend.')
    expect(wrapper.findAll('[data-resource-server-id]').length).toBe(0)
  })

  it('create: a slug conflict lands on the slug field, exactly one role="alert" in the DOM', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/resource-servers?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/resource-servers' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({
                error: 'resource_server.slug_conflict',
                error_description: 'Resource server slug already in use in this tenant: billing-api',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRSRouter()
    const wrapper = mount(TenantResourceServersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-rs-name').setValue('Billing API')
    await wrapper.find('#new-rs-slug').setValue('billing-api')
    await wrapper.find('#new-rs-audience').setValue('https://acme.rs.closeauth.io/billing-api')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const slugError = wrapper.find('#new-rs-slug-error')
    expect(slugError.exists()).toBe(true)
    expect(wrapper.find('#new-rs-slug').attributes('aria-invalid')).toBe('true')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('create: success closes the dialog state and refreshes the list', async () => {
    let listCallCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/resource-servers' && init?.method === 'POST') {
          return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve(rsFixture({ id: 'new-rs' })) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=20') {
          listCallCount += 1
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 20, totalElements: listCallCount - 1, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRSRouter()
    const wrapper = mount(TenantResourceServersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-rs-name').setValue('New RS')
    await wrapper.find('#new-rs-slug').setValue('new-rs')
    await wrapper.find('#new-rs-audience').setValue('https://acme.rs.closeauth.io/new-rs')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(listCallCount).toBe(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
