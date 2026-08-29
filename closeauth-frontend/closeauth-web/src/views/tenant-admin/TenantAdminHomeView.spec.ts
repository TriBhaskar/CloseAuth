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
      { path: '/t/:slug/console/audit', component: { template: '<div />' } },
    ],
  })
  await router.push('/t/acme/console')
  await router.isReady()
  return router
}

function pageResponse(totalElements: number) {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve({ items: [], page: 0, size: 1, totalElements, totalPages: 1 }),
  }
}

function auditPageResponse(items: unknown[] = []) {
  return {
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({ items, page: 0, size: 10, totalElements: items.length, totalPages: 1 }),
  }
}

function eventFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'event-1',
    eventType: 'USER_CREATED',
    outcome: 'SUCCESS',
    tenantId: 'tenant-1',
    actorUserId: 'admin-1',
    createdAt: '2026-01-01T00:00:00Z',
    eventData: {},
    ...overrides,
  }
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
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ count: 3 }),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1')
          return Promise.resolve(pageResponse(5))
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
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ count: 0 }),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1')
          return Promise.resolve(pageResponse(0))
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
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ count: 1 }),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1')
          return Promise.resolve(pageResponse(1))
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
            json: () =>
              Promise.resolve({
                error: 'bad_gateway',
                error_description: 'Could not reach the backend.',
              }),
          })
        }
        if (url === '/t/acme/api/clients/count') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ count: 2 }),
          })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=1')
          return Promise.resolve(pageResponse(2))
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
    // FE-6.1: the errored tile is a plain non-navigating element, never a
    // RouterLink doubling as both the error message and a click target.
    expect(usersTile.element.tagName).toBe('DIV')
  })

  it('FE-6.1: Retry on a failed tile re-fetches only that tile and recovers, without touching the others', async () => {
    let usersShouldFail = true
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=1') {
          if (usersShouldFail) {
            return Promise.resolve({
              ok: false,
              status: 502,
              json: () =>
                Promise.resolve({
                  error: 'bad_gateway',
                  error_description: 'Could not reach the backend.',
                }),
            })
          }
          return Promise.resolve(pageResponse(7))
        }
        if (url === '/t/acme/api/clients/count')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ count: 2 }),
          })
        if (url === '/t/acme/api/resource-servers?page=0&size=1')
          return Promise.resolve(pageResponse(2))
        if (url === '/t/acme/api/roles?page=0&size=1') return Promise.resolve(pageResponse(2))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('#overview-tile-users [role="alert"]').exists()).toBe(true)

    usersShouldFail = false
    await wrapper.find('#overview-tile-users button').trigger('click')
    await flushPromises()

    expect(wrapper.find('#overview-tile-users').text()).toContain('7')
    expect(wrapper.find('#overview-tile-clients').text()).toContain('2')
  })
})

// FE-5.8 (spec §6.4.1): the overview's second block. Loads on its own
// promise — the defining acceptance test is that killing the audit endpoint
// never touches the tiles.
describe('TenantAdminHomeView — recent activity (FE-5.8)', () => {
  function tileFetchHandlers(url: string) {
    if (url === '/t/acme/api/users?page=0&size=1') return pageResponse(1)
    if (url === '/t/acme/api/clients/count')
      return { ok: true, status: 200, json: () => Promise.resolve({ count: 1 }) }
    if (url === '/t/acme/api/resource-servers?page=0&size=1') return pageResponse(1)
    if (url === '/t/acme/api/roles?page=0&size=1') return pageResponse(1)
    return null
  }

  it('renders the last events independently of the tiles', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const tileResponse = tileFetchHandlers(url)
        if (tileResponse) return Promise.resolve(tileResponse)
        if (url === '/t/acme/api/audit-events?page=0&size=10')
          return Promise.resolve(auditPageResponse([eventFixture()]))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-activity-event-id="event-1"]').text()).toContain('USER_CREATED')
  })

  it('a failing audit call leaves all four tiles fully rendered, and shows its own inline error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const tileResponse = tileFetchHandlers(url)
        if (tileResponse) return Promise.resolve(tileResponse)
        if (url === '/t/acme/api/audit-events?page=0&size=10') {
          return Promise.resolve({
            ok: false,
            status: 502,
            json: () =>
              Promise.resolve({
                error: 'bad_gateway',
                error_description: 'Could not reach the backend.',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#overview-tile-users').text()).toContain('1')
    expect(wrapper.find('#overview-tile-clients').text()).toContain('1')
    expect(wrapper.find('#overview-tile-resource-servers').text()).toContain('1')
    expect(wrapper.find('#overview-tile-roles').text()).toContain('1')
    expect(wrapper.text()).toContain('Could not reach the backend.')
  })

  it('an empty tenant shows "No activity yet." — distinct from the audit log\'s own first-run copy but equally honest', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const tileResponse = tileFetchHandlers(url)
        if (tileResponse) return Promise.resolve(tileResponse)
        if (url === '/t/acme/api/audit-events?page=0&size=10')
          return Promise.resolve(auditPageResponse([]))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('No activity yet.')
  })

  it('links to the full audit log', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const tileResponse = tileFetchHandlers(url)
        if (tileResponse) return Promise.resolve(tileResponse)
        if (url === '/t/acme/api/audit-events?page=0&size=10')
          return Promise.resolve(auditPageResponse([]))
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#overview-view-all-activity').attributes('href')).toBe(
      '/t/acme/console/audit',
    )
  })
})
