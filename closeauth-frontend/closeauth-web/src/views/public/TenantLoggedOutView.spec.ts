import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia } from 'pinia'
import TenantLoggedOutView from './TenantLoggedOutView.vue'

afterEach(() => {
  vi.unstubAllGlobals()
})

async function mountAt(path: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/logged-out', component: TenantLoggedOutView },
      { path: '/t/:slug', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  // TenantBrandingProvider's useThemeStore() needs an active Pinia to mount.
  return { wrapper: mount(TenantLoggedOutView, { global: { plugins: [router, createPinia()] } }), router }
}

describe('TenantLoggedOutView', () => {
  it('renders a branded sign-out confirmation with a link back to the resolver', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        expect(url).toBe('/branding?client_id=admin-console-ten_acme-inc')
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              logoUrl: '',
              primaryColor: '',
              backgroundColor: '',
              accentColor: '',
              companyName: 'Acme Inc',
              tenantSlug: 'ten_acme-inc',
            }),
        })
      }),
    )

    const { wrapper } = await mountAt('/t/ten_acme-inc/logged-out')
    await flushPromises()

    expect(wrapper.text()).toContain("You've been signed out")
    const link = wrapper.findAll('a').find((a) => a.text().includes('Sign in again'))
    expect(link?.attributes('href')).toBe('/t/ten_acme-inc')
  })
})
