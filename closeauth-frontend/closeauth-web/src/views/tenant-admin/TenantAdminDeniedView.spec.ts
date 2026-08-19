import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAdminDeniedView from './TenantAdminDeniedView.vue'

// Stage UI-3a's required component tests for the "loop break A" fix: this
// view must do NOTHING automatically on mount (a refusal page that itself
// re-triggered auth would recreate the very loop this stage exists to
// prevent) — only the explicit "try a different account" action may
// navigate, and only after dismissing the denial marker first.

async function createDeniedRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/t/:slug/denied', component: TenantAdminDeniedView },
    ],
  })
  await router.push(query ? `/t/acme/denied?${query}` : '/t/acme/denied')
  await router.isReady()
  return router
}

beforeEach(() => {
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { assign: vi.fn() },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAdminDeniedView', () => {
  it('renders the refusal message and performs zero navigation on mount', async () => {
    const router = await createDeniedRouter('reason=not_tenant_admin')
    const wrapper = mount(TenantAdminDeniedView, { global: { plugins: [router] } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('TENANT_ADMIN')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  // FE-4d: the callback's own remaining denial reason (client_id/tenant_id
  // binding mismatch — the only thing left it can refuse a session for).
  it('renders the invalid_client_binding message', async () => {
    const router = await createDeniedRouter('reason=invalid_client_binding')
    const wrapper = mount(TenantAdminDeniedView, { global: { plugins: [router] } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('account or client does not match this tenant')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('"Try a different account" dismisses the denial marker, then navigates to admin/login', async () => {
    const dismissFetch = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/denied/dismiss') return dismissFetch()
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDeniedRouter('reason=not_tenant_admin')
    const wrapper = mount(TenantAdminDeniedView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#tenant-admin-denied-retry').trigger('click')
    await flushPromises()

    expect(dismissFetch).toHaveBeenCalledTimes(1)
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/login')
  })
})
