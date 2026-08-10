import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAdminHomeView from './TenantAdminHomeView.vue'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'

// Stage UI-3a's required component tests: the landing page renders session
// data from the store and the live ping result, and — honoring
// stores/admin.ts's standing rule — shows a visible [role="alert"] on a
// ping failure rather than ever fabricating data.

async function createHomeRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/console', component: TenantAdminHomeView }],
  })
  await router.push('/t/acme/console')
  await router.isReady()
  return router
}

function activeSession() {
  return {
    kind: 'active' as const,
    tenantId: 'tenant-1',
    userId: 'user-1',
    email: 'admin@acme.test',
    tenantRoles: ['TENANT_ADMIN'],
    accessTokenExpiresAt: '2026-01-01T00:00:00Z',
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { assign: vi.fn(), pathname: '/t/acme/console', search: '' },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubFetch(pingHandler: () => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/t/acme/api/ping') return pingHandler()
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

describe('TenantAdminHomeView', () => {
  it('renders session data and a successful ping result', async () => {
    stubFetch(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ok: true, tenantId: 'tenant-1' }) }),
    )

    const router = await createHomeRouter()
    const store = useTenantAdminSessionStore()
    store.slug = 'acme'
    store.state = activeSession()

    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#tenant-admin-home-slug').text()).toBe('acme')
    expect(wrapper.find('#tenant-admin-home-email').text()).toBe('admin@acme.test')
    expect(wrapper.text()).toContain('TENANT_ADMIN')
    expect(wrapper.find('#tenant-admin-home-ping').text()).toContain('tenant-1')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows a visible error, never fabricated data, when the ping fails', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: false,
        status: 502,
        json: () => Promise.resolve({ error: 'bad_gateway', error_description: 'Could not reach the backend.' }),
      }),
    )

    const router = await createHomeRouter()
    const store = useTenantAdminSessionStore()
    store.slug = 'acme'
    store.state = activeSession()

    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Could not reach the backend.')
    expect(wrapper.text()).not.toContain('Backend confirmed')
  })

  it('sign-out posts to /signout and navigates home', async () => {
    const signoutFetch = vi.fn().mockResolvedValue({ ok: true, status: 204, json: () => Promise.resolve({}) })
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/ping') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ok: true, tenantId: 'tenant-1' }) })
        }
        if (url === '/t/acme/api/signout') return signoutFetch()
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createHomeRouter()
    const store = useTenantAdminSessionStore()
    store.slug = 'acme'
    store.state = activeSession()

    const wrapper = mount(TenantAdminHomeView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#tenant-admin-home-signout').trigger('click')
    await flushPromises()

    expect(signoutFetch).toHaveBeenCalledTimes(1)
    expect(window.location.assign).toHaveBeenCalledWith('/')
  })
})
