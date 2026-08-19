import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia } from 'pinia'
import axe from 'axe-core'
import LoginView from '@/views/auth/LoginView.vue'
import TenantUsersView from '@/views/tenant-admin/TenantUsersView.vue'
import WorkspaceEntryView from '@/views/public/WorkspaceEntryView.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-0.6: the quality-gate axe-core smoke check called for by the build
// plan ("an axe-core smoke check on three representative routes"). Three
// routes, one per surface, chosen to exercise genuinely different DOM shapes
// rather than three near-identical forms:
//   - entry:       WorkspaceEntryView (public, unauthenticated; FE-2a rebuilt
//                   this from FE-0's placeholder into the real screen)
//   - hosted auth: LoginView (a real form, branding-injected)
//   - console:     TenantUsersView (a data table + dialog, guarded surface)
//
// This is a SMOKE check, not the full accessibility pass (that's FE-6.3,
// manual, per spec §8). It exists to catch gross regressions (missing
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
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] }, attachTo: attachRoot() })
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
    const wrapper = mount(LoginView, { global: { plugins: [router, createPinia()] }, attachTo: attachRoot() })
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
            json: () => Promise.resolve({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/t/:slug/console/users', name: 'tenant-admin-users', component: TenantUsersView },
        { path: '/t/:slug/console/users/:userId', name: 'tenant-admin-user-detail', component: { template: '<div />' } },
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
})
