import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantApplicationRoleDetailView from './TenantApplicationRoleDetailView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3d: application-role detail — edit form + scope bundling. The
// headline proof here is structural, not behavioral: the fetch stub knows
// ONLY /resource-servers/rs-1/scopes (this role's own RS) and rejects every
// other scope-catalog URL with "unexpected fetch" — so a passing test IS the
// evidence that no cross-RS scope is ever requested, let alone offered.
async function createDetailRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/t/:slug/console/resource-servers/:rsId',
        name: 'tenant-admin-resource-server-detail',
        component: { template: '<div />' },
      },
      {
        path: '/t/:slug/console/resource-servers/:rsId/roles/:roleId',
        name: 'tenant-admin-application-role-detail',
        component: TenantApplicationRoleDetailView,
      },
    ],
  })
  await router.push('/t/acme/console/resource-servers/rs-1/roles/role-1')
  await router.isReady()
  return router
}

function roleFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'role-1',
    resourceServerId: 'rs-1',
    tenantId: 'tenant-1',
    name: 'READER',
    description: 'Reader role',
    isDefault: false,
    isSystem: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

const rsScopes = [
  { id: 'scope-1', resourceServerId: 'rs-1', scopeName: 'read', description: 'Read', isDefault: false, requiresConsent: false, createdAt: '' },
  { id: 'scope-2', resourceServerId: 'rs-1', scopeName: 'write', description: 'Write', isDefault: false, requiresConsent: false, createdAt: '' },
]

function stubBaseFetch(bundledScopeIds: string[]) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(roleFixture()) })
    }
    // Deliberately the ONLY scope-catalog URL this stub answers — a role on
    // a different RS, or any other RS's scopes, is not in this list at all.
    if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=100') {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: rsScopes, page: 0, size: 100, totalElements: 2, totalPages: 1 }),
      })
    }
    if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1/scopes?page=0&size=100') {
      const bundled = rsScopes.filter((s) => bundledScopeIds.includes(s.id))
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: bundled, page: 0, size: 100, totalElements: bundled.length, totalPages: 1 }),
      })
    }
    if (url === '/api/csrf') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
    }
    return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
  })
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantApplicationRoleDetailView', () => {
  it('renders the role name read-only and the description/isDefault edit form pre-populated', async () => {
    vi.stubGlobal('fetch', stubBaseFetch([]))

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#app-role-name-readonly').text()).toBe('READER')
    expect((wrapper.find('#app-role-edit-description').element as HTMLInputElement).value).toBe('Reader role')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('the scope picker is fed ONLY by this role\'s own RS — a passing test proves no cross-RS scope is offerable', async () => {
    vi.stubGlobal('fetch', stubBaseFetch(['scope-1']))

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    // Both of this RS's scopes are rendered (proves the catalog fetch hit the
    // single stubbed URL, not rejected as "unexpected fetch").
    const readCheckbox = wrapper.find('#app-role-scope-scope-1')
    const writeCheckbox = wrapper.find('#app-role-scope-scope-2')
    expect(readCheckbox.exists()).toBe(true)
    expect(writeCheckbox.exists()).toBe(true)
    expect(readCheckbox.attributes('aria-checked')).toBe('true')
    expect(writeCheckbox.attributes('aria-checked')).toBe('false')
  })

  it('toggling an unbundled scope issues POST .../roles/role-1/scopes/scope-2', async () => {
    const fetchMock = stubBaseFetch(['scope-1'])
    const withAdd = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1/scopes/scope-2' && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withAdd)

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#app-role-scope-scope-2').trigger('click')
    await flushPromises()

    expect(withAdd).toHaveBeenCalledWith(
      '/t/acme/api/resource-servers/rs-1/roles/role-1/scopes/scope-2',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('a defensive scope_rs_mismatch renders a written explanation, never the raw errors map (RS UUIDs)', async () => {
    const fetchMock = stubBaseFetch(['scope-1'])
    const withMismatch = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1/scopes/scope-2' && init?.method === 'POST') {
        return Promise.resolve({
          ok: false,
          status: 400,
          json: () =>
            Promise.resolve({
              error: 'application_role.scope_rs_mismatch',
              error_description: 'Scope belongs to a different resource server than the application role',
              errors: { roleResourceServerId: 'rs-1', scopeResourceServerId: 'rs-9' },
            }),
        })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withMismatch)

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#app-role-scope-scope-2').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('cannot be bundled into this role')
    // Never the raw RS-UUID-keyed errors map.
    expect(wrapper.text()).not.toContain('rs-9')
    expect(wrapper.text()).not.toContain('roleResourceServerId')
  })

  it('edit form: description/isDefault are sent together as a full replacement', async () => {
    const fetchMock = stubBaseFetch([])
    const withPatch = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1' && init?.method === 'PATCH') {
        const body = JSON.parse(String(init?.body))
        expect(body).toEqual({ description: 'Updated', isDefault: true })
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(roleFixture({ description: 'Updated', isDefault: true })),
        })
      }
      return fetchMock(url, init)
    })
    vi.stubGlobal('fetch', withPatch)

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#app-role-edit-description').setValue('Updated')
    await wrapper.find('#app-role-edit-is-default').trigger('click')
    await wrapper.find('#app-role-edit-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows a visible error, no form, when the role fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1/roles/role-1') {
          return Promise.resolve({
            ok: false,
            status: 404,
            json: () => Promise.resolve({ error: 'application_role.not_found', error_description: 'Role not found.' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantApplicationRoleDetailView, { global: { plugins: [router] } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Role not found.')
    expect(wrapper.find('#app-role-name-readonly').exists()).toBe(false)
  })
})
