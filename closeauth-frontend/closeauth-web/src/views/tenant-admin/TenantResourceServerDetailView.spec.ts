import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantResourceServerDetailView from './TenantResourceServerDetailView.vue'
import { clearCsrfToken } from '@/api/csrf'

// Stage UI-3c: audience is read-only TEXT, never an editable input
// (immutable after creation); no delete control renders for an
// autoCreated RS (replaced by an explanation); and a scope edit always
// submits all three mutable fields together, matching the backend's
// full-replacement PATCH semantics (updateScope sets description/isDefault/
// requiresConsent unconditionally — never a sparse merge).
//
// FE-4b: the scope catalog is now DataTable-based, fetched at
// SCOPE_CATALOG_PAGE_SIZE=100 (was 20) and filtered client-side (no
// server-side scope search exists). ApplicationRolesPanel (embedded below
// the scopes card) is ALSO always mounted and fetches its own roles list on
// mount — every test's fetch mock must stub that URL too, or the mock's
// catch-all reject becomes an unhandled rejection (the exact a11ySmoke bug
// FE-4a hit and fixed for the same reason).
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createDetailRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/resource-servers', name: 'tenant-admin-resource-servers', component: { template: '<div />' } },
      {
        path: '/t/:slug/console/resource-servers/:rsId',
        name: 'tenant-admin-resource-server-detail',
        component: TenantResourceServerDetailView,
      },
      {
        path: '/t/:slug/console/resource-servers/:rsId/roles/:roleId',
        name: 'tenant-admin-application-role-detail',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/t/acme/console/resource-servers/rs-1')
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
    scopeCount: 1,
    ...overrides,
  }
}

const emptyScopesPage = { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }
const emptyAppRolesPage = { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }

function scopeFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'scope-1',
    resourceServerId: 'rs-1',
    scopeName: 'read',
    description: 'Read access',
    isDefault: true,
    requiresConsent: false,
    createdAt: '2026-01-01T00:00:00Z',
    usedByRoleCount: 2,
    ...overrides,
  }
}

// Every test needs both the RS's own scopes AND ApplicationRolesPanel's
// roles list stubbed — the panel is unconditionally mounted alongside the
// scopes card. Callers layer their own scope-list/mutation stubs on top.
function baseFetch(extra: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/t/acme/api/resource-servers/rs-1/roles?page=0&size=100') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyAppRolesPage) })
    }
    const handled = extra(url, init)
    if (handled) return handled
    return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
  })
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantResourceServerDetailView', () => {
  it('audience is rendered as read-only text, not an input', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch((url) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const audienceEl = wrapper.find('#rs-audience')
    expect(audienceEl.exists()).toBe(true)
    expect(audienceEl.element.tagName).toBe('CODE')
    expect(audienceEl.text()).toBe('https://acme.rs.closeauth.io/billing-api')
    // Never an input the admin could type into.
    expect(wrapper.find('input#rs-audience').exists()).toBe(false)
  })

  it('standalone RS: delete control is present', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch((url) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture({ autoCreated: false })) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#rs-delete-button').exists()).toBe(true)
  })

  it('auto-created RS: NO delete control renders — an explanation stands in its place', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch((url) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture({ autoCreated: true })) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#rs-delete-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('created automatically with its client and cannot be deleted directly')
  })

  it('scope catalog shows the slug-prefixed name and the "used by N roles" count', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch((url) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [scopeFixture()], page: 0, size: 100, totalElements: 1, totalPages: 1 }),
          }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const row = wrapper.find('[data-scope-id="scope-1"]')
    expect(row.text()).toContain('billing-api:read')
    expect(row.text()).toContain('2 roles')
  })

  it('scope edit submits all three mutable fields together (full replacement, never a sparse patch)', async () => {
    const patchBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      baseFetch((url, init) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [scopeFixture()], page: 0, size: 100, totalElements: 1, totalPages: 1 }),
          }) as unknown as Response
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes/scope-1' && init?.method === 'PATCH') {
          patchBodies.push(JSON.parse(String(init.body)))
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve(scopeFixture({ description: 'Updated', isDefault: false, requiresConsent: true })),
          }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#scope-edit-scope-1').trigger('click')
    await wrapper.find('#scope-description').setValue('Updated')
    // Toggle both booleans away from their fixture defaults.
    await wrapper.find('#scope-is-default').trigger('click')
    await wrapper.find('#scope-requires-consent').trigger('click')
    await wrapper.find('#scope-form').trigger('submit.prevent')
    await flushPromises()

    expect(patchBodies).toHaveLength(1)
    expect(patchBodies[0]).toEqual({ description: 'Updated', isDefault: false, requiresConsent: true })
  })

  it('scope edit dialog shows the scope name read-only — no editable field for it', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch((url) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) }) as unknown as Response
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [scopeFixture()], page: 0, size: 100, totalElements: 1, totalPages: 1 }),
          }) as unknown as Response
        }
        return undefined
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#scope-edit-scope-1').trigger('click')

    expect(wrapper.find('#scope-name-readonly').exists()).toBe(true)
    expect(wrapper.find('#scope-name-readonly').text()).toBe('read')
    expect(wrapper.find('input#scope-name').exists()).toBe(false)
  })

  it('application roles panel renders roles from a real fetch and links to the role detail route', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/roles?page=0&size=100') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [{ id: 'app-role-1', resourceServerId: 'rs-1', tenantId: 'tenant-1', name: 'Invoice reader', description: null, isDefault: false, isSystem: false, createdAt: '', updatedAt: '' }],
                page: 0,
                size: 100,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-application-role-id="app-role-1"]').text()).toContain('Invoice reader')
  })
})
