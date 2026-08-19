import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAdminHomeView from './TenantAdminHomeView.vue'

// FE-4d (spec §6.4.1): the console overview's four count tiles, rebuilt off
// the UI-3a foundation stub (session dump + ping widget, both retired —
// every tile here makes its own genuine authenticated API call, which
// already proves backend connectivity, so a separate ping check would be
// redundant).

async function createHomeRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console', component: TenantAdminHomeView },
      { path: '/t/:slug/console/users', component: { template: '<div />' } },
      { path: '/t/:slug/console/clients', component: { template: '<div />' } },
      { path: '/t/:slug/console/resource-servers', component: { template: '<div />' } },
      { path: '/t/:slug/console/roles', component: { template: '<div />' } },
    ],
  })
  await router.push('/t/acme/console')
  await router.isReady()
  return router
}

function pageResponse(totalElements: number) {
  return { ok: true, status: 200, json: () => Promise.resolve({ items: [], page: 0, size: 1, totalElements, totalPages: 1 }) }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAdminHomeView', () => {
  it('renders a real count for each populated tile', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=1') return Promise.resolve(pageResponse(12))
        if (url === '/t/acme/api/clients/count') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 3 }) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1') return Promise.resolve(pageResponse(5))
        if (url === '/t/acme/api/roles?page=0&size=1') return Promise.resolve(pageResponse(4))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#overview-tile-users').text()).toContain('12')
    expect(wrapper.find('#overview-tile-clients').text()).toContain('3')
    expect(wrapper.find('#overview-tile-resource-servers').text()).toContain('5')
    expect(wrapper.find('#overview-tile-roles').text()).toContain('4')
  })

  it('a zero-count tile shows a create prompt instead of "0"', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=1') return Promise.resolve(pageResponse(0))
        if (url === '/t/acme/api/clients/count') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1') return Promise.resolve(pageResponse(0))
        if (url === '/t/acme/api/roles?page=0&size=1') return Promise.resolve(pageResponse(0))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    const usersTile = wrapper.find('#overview-tile-users')
    expect(usersTile.text()).not.toContain('0')
    expect(usersTile.text()).toContain('Add your first user')
    expect(wrapper.find('#overview-tile-clients').text()).toContain('Register your first client')
  })

  it('each tile links to its own console section', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=1') return Promise.resolve(pageResponse(1))
        if (url === '/t/acme/api/clients/count') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 1 }) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1') return Promise.resolve(pageResponse(1))
        if (url === '/t/acme/api/roles?page=0&size=1') return Promise.resolve(pageResponse(1))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#overview-tile-clients').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/t/acme/console/clients')
  })

  it('a failed tile shows a visible error, never fabricated data', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=1') {
          return Promise.resolve({
            ok: false,
            status: 502,
            json: () => Promise.resolve({ error: 'bad_gateway', error_description: 'Could not reach the backend.' }),
          })
        }
        if (url === '/t/acme/api/clients/count') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 2 }) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1') return Promise.resolve(pageResponse(2))
        if (url === '/t/acme/api/roles?page=0&size=1') return Promise.resolve(pageResponse(2))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    const usersTile = wrapper.find('#overview-tile-users')
    expect(usersTile.find('[role="alert"]').exists()).toBe(true)
    // The other three tiles are unaffected by the users tile's failure.
    expect(wrapper.find('#overview-tile-clients').text()).toContain('2')
  })
})
