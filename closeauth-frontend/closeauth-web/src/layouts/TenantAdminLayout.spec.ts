import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAdminLayout from './TenantAdminLayout.vue'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'

// FE-4d: this layout is now shared by /console (admin-only content) and
// /account (spec §6.4.8 — every tenant user, admin or not). navItems must
// reflect that: an admin sees the full nav plus "My account"; a non-admin
// sees only "My account" — nothing else is reachable to them regardless.
function stubMatchMedia() {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: true,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  })
}

async function mountLayout() {
  setActivePinia(createPinia())
  stubMatchMedia()

  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/t/:slug/console',
        name: 'tenant-admin-console',
        component: { template: '<div id="page-content" />' },
        meta: { title: 'Overview' },
      },
    ],
  })
  await router.push('/t/acme/console')
  await router.isReady()

  const wrapper = mount(TenantAdminLayout, { global: { plugins: [router] } })
  const store = useTenantAdminSessionStore()
  return { wrapper, store }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAdminLayout — FE-4d role-conditional nav', () => {
  it('an admin session sees the full nav plus "My account"', async () => {
    const { wrapper, store } = await mountLayout()
    store.state = {
      kind: 'active',
      tenantId: 't-1',
      userId: 'u-1',
      email: 'ada@acme.test',
      tenantRoles: ['TENANT_ADMIN'],
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    }
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Users')
    expect(text).toContain('Clients')
    expect(text).toContain('Resource servers')
    expect(text).toContain('Roles')
    expect(text).toContain('Audit log')
    expect(text).toContain('Settings')
    expect(text).toContain('My account')
  })

  it('a non-admin session sees ONLY "My account" — nothing else is reachable to them', async () => {
    const { wrapper, store } = await mountLayout()
    store.state = {
      kind: 'active',
      tenantId: 't-1',
      userId: 'u-2',
      email: 'nonadmin@acme.test',
      tenantRoles: [],
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    }
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('My account')
    expect(text).not.toContain('Users')
    expect(text).not.toContain('Clients')
    expect(text).not.toContain('Resource servers')
    expect(text).not.toContain('Audit log')
    expect(text).not.toContain('Settings')
  })
})
