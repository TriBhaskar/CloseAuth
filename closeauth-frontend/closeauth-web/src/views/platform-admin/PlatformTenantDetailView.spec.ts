import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformTenantDetailView from './PlatformTenantDetailView.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-3b (spec §6.3.3): the tenant detail page's required proofs — header
// (Tenant ID chip, status, sign-in URL), the Admins section's two states
// (reliable adminCount even without a real per-admin list — see the
// session's own tracked-gap decision), Lifecycle transitions spelled out
// (suspend/activate only — delete lives in its own Danger Zone, matching
// spec's own section split), the Danger Zone's typed-delete gate navigating
// back to the list on success, and the known 403 tenant.not_active
// mid-bootstrap recovery copy.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createDetailRouter(tenantId = 'tenant-1') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/platform/console/tenants', name: 'platform-admin-tenants', component: { template: '<div id="list-stub" />' } },
      { path: '/platform/console/tenants/:tenantId', name: 'platform-admin-tenant-detail', component: PlatformTenantDetailView },
    ],
  })
  await router.push(`/platform/console/tenants/${tenantId}`)
  await router.isReady()
  return router
}

function tenantFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'tenant-1',
    slug: 'ten_acme-inc',
    name: 'Acme Inc',
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    adminCount: 0,
    ...overrides,
  }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubFetch(handler: (url: string, init?: RequestInit) => { ok: boolean; status: number; body: unknown } | null) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/csrf') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
      }
      const result = handler(url, init)
      if (result) {
        return Promise.resolve({ ok: result.ok, status: result.status, json: () => Promise.resolve(result.body), clone() { return this } })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

describe('PlatformTenantDetailView', () => {
  it('renders the header: name, Tenant ID chip, status badge, created, and a copyable sign-in URL', async () => {
    stubFetch((url) => (url === '/platform/api/tenants/tenant-1' ? { ok: true, status: 200, body: tenantFixture() } : null))
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#tenant-detail-name').text()).toBe('Acme Inc')
    expect(wrapper.text()).toContain('ten_acme-inc')
    expect(wrapper.text()).toContain('ACTIVE')
    expect(wrapper.find('#tenant-detail-signin-url').text()).toContain('/t/ten_acme-inc/login')
  })

  it('a not-found/unreachable tenant shows an error, not a raw crash', async () => {
    stubFetch((url) => (url === '/platform/api/tenants/tenant-1' ? { ok: false, status: 404, body: { error: 'tenant.not_found', error_description: 'nope' } } : null))
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.find('#tenant-detail-name').exists()).toBe(false)
  })

  it('Admins: adminCount=0 on an ACTIVE tenant shows the Incomplete state and a Bootstrap admin action', async () => {
    stubFetch((url) => (url === '/platform/api/tenants/tenant-1' ? { ok: true, status: 200, body: tenantFixture({ adminCount: 0 }) } : null))
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.find('#tenant-detail-bootstrap').exists()).toBe(true)
    expect(wrapper.find('#tenant-detail-reissue').exists()).toBe(false)
  })

  it('Admins: adminCount>0 offers Reissue a credential instead, no Incomplete flag', async () => {
    stubFetch((url) => (url === '/platform/api/tenants/tenant-1' ? { ok: true, status: 200, body: tenantFixture({ adminCount: 2 }) } : null))
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Incomplete')
    expect(wrapper.find('#tenant-detail-reissue').exists()).toBe(true)
    expect(wrapper.find('#tenant-detail-bootstrap').exists()).toBe(false)
  })

  it('Lifecycle: an ACTIVE tenant offers only Suspend (spelled-out effect, no Delete here — Danger Zone owns that)', async () => {
    stubFetch((url) => (url === '/platform/api/tenants/tenant-1' ? { ok: true, status: 200, body: tenantFixture({ status: 'ACTIVE' }) } : null))
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#tenant-detail-action-suspend').exists()).toBe(true)
    expect(wrapper.find('#tenant-detail-action-activate').exists()).toBe(false)
    expect(wrapper.find('#tenant-detail-action-delete').exists()).toBe(false)
    expect(wrapper.text()).toContain("won't be able to sign in")
  })

  it('Lifecycle: suspending re-fetches the tenant in place (no navigation)', async () => {
    let status = 'ACTIVE'
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1' && (!init || init.method === undefined || init.method === 'GET')) {
        return { ok: true, status: 200, body: tenantFixture({ status }) }
      }
      if (url === '/platform/api/tenants/tenant-1/suspend' && init?.method === 'POST') {
        status = 'SUSPENDED'
        return { ok: true, status: 200, body: tenantFixture({ status }) }
      }
      return null
    })
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-detail-action-suspend').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('platform-admin-tenant-detail')
    expect(wrapper.text()).toContain('SUSPENDED')
  })

  it('Danger Zone: delete requires the exact Tenant ID typed, and navigates back to the list on success', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1' && (!init || init.method === undefined || init.method === 'GET')) {
        return { ok: true, status: 200, body: tenantFixture({ status: 'ACTIVE' }) }
      }
      if (url === '/platform/api/tenants/tenant-1' && init?.method === 'DELETE') {
        return { ok: true, status: 200, body: tenantFixture({ status: 'DELETED' }) }
      }
      return null
    })
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('Danger zone')
    await wrapper.find('#tenant-detail-delete').trigger('click')
    await flushPromises()

    const confirmButton = wrapper.find('#typed-confirm-dialog-confirm')
    expect(confirmButton.attributes('disabled')).toBeDefined()

    await wrapper.find('#typed-confirm-input').setValue('ten_acme-inc')
    await flushPromises()
    expect(confirmButton.attributes('disabled')).toBeUndefined()

    await confirmButton.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('platform-admin-tenants')
  })

  it('a 403 tenant.not_active mid-bootstrap shows the spec-literal recovery copy, form stays resubmittable', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1' && (!init || init.method === undefined || init.method === 'GET')) {
        return { ok: true, status: 200, body: tenantFixture({ status: 'ACTIVE', adminCount: 0 }) }
      }
      if (url === '/platform/api/tenants/tenant-1/bootstrap-admin' && init?.method === 'POST') {
        return { ok: false, status: 403, body: { error: 'tenant.not_active', error_description: 'not active' } }
      }
      return null
    })
    const router = await createDetailRouter()
    const wrapper = mount(PlatformTenantDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#tenant-detail-bootstrap').trigger('click')
    await flushPromises()
    await wrapper.find('#tenant-detail-bootstrap-email').setValue('ada@acme.test')
    await wrapper.find('#tenant-detail-bootstrap-confirm-email').setValue('ada@acme.test')
    await wrapper.find('#tenant-detail-bootstrap-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain("Tenant created, but the admin couldn't be added yet. Retry.")
    expect(wrapper.find('#tenant-detail-bootstrap-form').exists()).toBe(true)
  })
})
