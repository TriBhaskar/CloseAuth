import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia } from 'pinia'
import axe from 'axe-core'
import LoginView from '@/views/auth/LoginView.vue'
import RegisterView from '@/views/auth/RegisterView.vue'
import ConsentView from '@/views/auth/ConsentView.vue'
import TenantUsersView from '@/views/tenant-admin/TenantUsersView.vue'
import TenantUserDetailView from '@/views/tenant-admin/TenantUserDetailView.vue'
import TenantAuditView from '@/views/tenant-admin/TenantAuditView.vue'
import TenantSettingsView from '@/views/tenant-admin/TenantSettingsView.vue'
import TenantAdminDeniedView from '@/views/tenant-admin/TenantAdminDeniedView.vue'
import WorkspaceEntryView from '@/views/public/WorkspaceEntryView.vue'
import NotFoundView from '@/views/public/NotFoundView.vue'
import PlatformLoginView from '@/views/platform-admin/PlatformLoginView.vue'
import PlatformTenantsView from '@/views/platform-admin/PlatformTenantsView.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-0.6 / FE-6.3: the quality-gate axe-core smoke check. Started at three
// routes ("an axe-core smoke check on three representative routes"); FE-6.3
// widened it to twelve, chosen for genuinely distinct DOM shape rather than
// raw route count — a dialog-bearing table, a tabbed detail page, a filter
// bar + table, a tabbed settings shell, native (non-fetch) POST forms, a
// denial page, and a terminal 404, spanning all three principal surfaces
// (hosted auth, tenant console, platform console):
//   - entry:            WorkspaceEntryView, NotFoundView
//   - hosted auth:      LoginView, RegisterView, ConsentView
//   - tenant console:   TenantUsersView, TenantUserDetailView, TenantAuditView, TenantSettingsView
//   - platform console: PlatformLoginView, PlatformTenantsView
//   - denial:           TenantAdminDeniedView
//
// This is a SMOKE check, not the full accessibility pass (that's FE-6.3's
// OTHER half — manual keyboard traversal and focus-ring verification per
// spec §8 — which needs a live rendered browser and isn't reachable from a
// jsdom test). It exists to catch gross structural regressions (missing
// labels, missing landmarks, broken heading order) from here on, not to be
// the accessibility floor itself.
//
// `color-contrast` is disabled: vitest.config.ts runs with `css: false` (no
// stylesheet pipeline in jsdom), so computed colors are meaningless here —
// enabling the rule would produce noise, not signal. Real contrast
// verification happens in FE-6.3/6.7 against rendered, styled output.
const AXE_OPTIONS: axe.RunOptions = {
  rules: { 'color-contrast': { enabled: false } },
}

async function runAxe(el: Element) {
  const results = await axe.run(el, AXE_OPTIONS)
  return results.violations
}

function formatViolations(violations: axe.Result[]): string {
  return violations
    .map((v) => `- [${v.id}] ${v.help} (${v.nodes.length} node(s)): ${v.helpUrl}`)
    .join('\n')
}

beforeEach(() => {
  clearCsrfToken()
  localStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

// axe-core requires its target to be attached to `document` — a detached
// @vue/test-utils wrapper (the default) reports "No elements found for
// include in page Context" rather than any real violation.
function attachRoot(): HTMLElement {
  const el = document.createElement('div')
  document.body.appendChild(el)
  return el
}

describe('a11y smoke (FE-0.6)', () => {
  it('entry — WorkspaceEntryView has no axe violations', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/', component: WorkspaceEntryView }],
    })
    await router.push('/')
    await router.isReady()

    // No closeauth.lastTenantId in localStorage (cleared in beforeEach), so
    // onMounted's remembered-tenant lookup short-circuits without a fetch —
    // this route needs no fetch stub, unlike the hosted-auth case below.
    const wrapper = mount(WorkspaceEntryView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('hosted auth — LoginView has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                logoUrl: '',
                primaryColor: '#4F46E5',
                backgroundColor: '#FFFFFF',
                accentColor: '#22D3EE',
                companyName: '',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/login', component: LoginView },
      ],
    })
    await router.push('/login')
    await router.isReady()

    // TenantBrandingProvider's useThemeStore() needs an active Pinia to mount.
    const wrapper = mount(LoginView, {
      global: { plugins: [router, createPinia()] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('console — TenantUsersView has no axe violations', async () => {
    const dialogStubs = {
      Dialog: { template: '<div><slot /></div>' },
      DialogTrigger: { template: '<div><slot /></div>' },
      DialogContent: { template: '<div><slot /></div>' },
      DialogHeader: { template: '<div><slot /></div>' },
      DialogFooter: { template: '<div><slot /></div>' },
      DialogTitle: { template: '<div><slot /></div>' },
      DialogDescription: { template: '<div><slot /></div>' },
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [
                  {
                    id: 'user-1',
                    tenantId: 'tenant-1',
                    email: 'alice@acme.test',
                    emailVerified: true,
                    phone: null,
                    phoneVerified: false,
                    firstName: 'Alice',
                    lastName: 'Admin',
                    status: 'ACTIVE',
                    lastLoginAt: null,
                    createdAt: '2026-01-01T00:00:00Z',
                    updatedAt: '2026-01-01T00:00:00Z',
                    roles: ['TENANT_ADMIN'],
                    isLastActiveAdmin: null,
                  },
                ],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/t/:slug/console/users', name: 'tenant-admin-users', component: TenantUsersView },
        {
          path: '/t/:slug/console/users/:userId',
          name: 'tenant-admin-user-detail',
          component: { template: '<div />' },
        },
      ],
    })
    await router.push('/t/acme/console/users')
    await router.isReady()

    const wrapper = mount(TenantUsersView, {
      global: { plugins: [router], stubs: dialogStubs },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('entry (terminal) — NotFoundView has no axe violations', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/not-found', component: NotFoundView },
      ],
    })
    await router.push('/not-found')
    await router.isReady()

    const wrapper = mount(NotFoundView, { global: { plugins: [router] }, attachTo: attachRoot() })
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('hosted auth — RegisterView has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                logoUrl: '',
                primaryColor: '#4F46E5',
                backgroundColor: '#FFFFFF',
                accentColor: '#22D3EE',
                companyName: '',
                tenantSlug: 'ten_acme-inc',
                registrationMode: null,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/t/:slug/register', component: RegisterView },
      ],
    })
    await router.push('/t/ten_acme-inc/register?client_id=admin-console-ten_acme-inc')
    await router.isReady()

    const wrapper = mount(RegisterView, {
      global: { plugins: [router, createPinia()] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('hosted auth — ConsentView has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/branding')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                logoUrl: '',
                primaryColor: '#4F46E5',
                backgroundColor: '#FFFFFF',
                accentColor: '#22D3EE',
                companyName: 'Acme Co',
                tenantSlug: null,
              }),
          })
        }
        if (url.startsWith('/oauth2/consent')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                clientId: 'app-123',
                clientName: 'Acme App',
                state: 'st-abc123',
                scopes: [
                  { scope: 'openid', description: 'Verify your identity', requiresConsent: false },
                  {
                    scope: 'todo-api:write',
                    description: 'Modify your to-do items',
                    requiresConsent: true,
                  },
                ],
                alreadyGranted: [],
                authorizeUrl: 'http://backend.test:9000/closeauth/oauth2/authorize',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/consent', component: ConsentView },
      ],
    })
    await router.push('/consent?client_id=app-123&scope=openid&state=st-abc123')
    await router.isReady()

    const wrapper = mount(ConsentView, {
      global: { plugins: [router, createPinia()] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('tenant console — TenantUserDetailView (tabbed detail) has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users/user-1') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                id: 'user-1',
                tenantId: 'tenant-1',
                email: 'alice@acme.test',
                emailVerified: true,
                phone: null,
                phoneVerified: false,
                firstName: 'Alice',
                lastName: 'Admin',
                status: 'ACTIVE',
                lastLoginAt: null,
                createdAt: '2026-01-01T00:00:00Z',
                updatedAt: '2026-01-01T00:00:00Z',
                roles: [],
                isLastActiveAdmin: null,
              }),
          })
        }
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/t/acme/api/users/user-1/tenant-roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        if (url === '/t/acme/api/resource-servers?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/t/acme/api/users/user-1/sessions') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        {
          path: '/t/:slug/console/users/:userId',
          name: 'tenant-admin-user-detail',
          component: TenantUserDetailView,
        },
      ],
    })
    await router.push('/t/acme/console/users/user-1')
    await router.isReady()

    const wrapper = mount(TenantUserDetailView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('tenant console — TenantAuditView (filter bar + table) has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/t/:slug/console/audit', name: 'tenant-admin-audit', component: TenantAuditView },
      ],
    })
    await router.push('/t/acme/console/audit')
    await router.isReady()

    const wrapper = mount(TenantAuditView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('tenant console — TenantSettingsView (tabbed shell) has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                logoUrl: '',
                primaryColor: '#4F46E5',
                backgroundColor: '#FFFFFF',
                accentColor: '#22D3EE',
                companyName: '',
              }),
          })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ tenantId: 'tenant-1', mode: 'EMAIL_VERIFIED' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        {
          path: '/t/:slug/console/settings',
          name: 'tenant-admin-settings',
          component: TenantSettingsView,
        },
      ],
    })
    await router.push('/t/acme/console/settings')
    await router.isReady()

    const wrapper = mount(TenantSettingsView, {
      global: { plugins: [router, createPinia()] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('denial — TenantAdminDeniedView has no axe violations', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/t/:slug/denied', name: 'tenant-admin-denied', component: TenantAdminDeniedView },
      ],
    })
    await router.push('/t/acme/denied?reason=not_tenant_admin')
    await router.isReady()

    const wrapper = mount(TenantAdminDeniedView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('platform console — PlatformLoginView (unbranded EntryShell form) has no axe violations', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/platform/login', name: 'platform-admin-login', component: PlatformLoginView },
      ],
    })
    await router.push('/platform/login')
    await router.isReady()

    const wrapper = mount(PlatformLoginView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })

  it('platform console — PlatformTenantsView (table + dialogs) has no axe violations', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/tenants?page=0&size=200') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        {
          path: '/platform/console/tenants',
          name: 'platform-admin-tenants',
          component: PlatformTenantsView,
        },
      ],
    })
    await router.push('/platform/console/tenants')
    await router.isReady()

    const wrapper = mount(PlatformTenantsView, {
      global: { plugins: [router] },
      attachTo: attachRoot(),
    })
    await flushPromises()
    const violations = await runAxe(wrapper.element)
    expect(violations, formatViolations(violations)).toHaveLength(0)
  })
})
