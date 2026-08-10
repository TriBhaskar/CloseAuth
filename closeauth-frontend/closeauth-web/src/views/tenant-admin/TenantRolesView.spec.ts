import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantRolesView from './TenantRolesView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3d: tenant-role list + CRUD — the same list-then-dialogs pattern
// TenantResourceServersView.vue (UI-3c) established, plus this surface's own
// required proof: a system role exposes NO edit/delete control at all (not
// a disabled one) — an action the backend would refuse (403
// role.system_immutable) is never offered in the first place.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createRolesRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/console/roles', name: 'tenant-admin-roles', component: TenantRolesView }],
  })
  await router.push('/t/acme/console/roles')
  await router.isReady()
  return router
}

function roleFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'role-1',
    tenantId: 'tenant-1',
    name: 'TENANT_ADMIN',
    description: 'Full admin',
    isDefault: false,
    isSystem: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantRolesView', () => {
  it('renders roles from a real PageView, no alert on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/roles?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [roleFixture(), roleFixture({ id: 'role-2', name: 'BILLING_ADMIN', isSystem: false })],
                page: 0,
                size: 20,
                totalElements: 2,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-role-id="role-1"]').text()).toContain('TENANT_ADMIN')
    expect(wrapper.find('[data-role-id="role-2"]').text()).toContain('BILLING_ADMIN')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('a system role exposes NO edit or delete control — the backend would refuse both with 403', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/roles?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [roleFixture(), roleFixture({ id: 'role-2', name: 'BILLING_ADMIN', isSystem: false })],
                page: 0,
                size: 20,
                totalElements: 2,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    // System role: neither control exists at all.
    expect(wrapper.find('#role-edit-role-1').exists()).toBe(false)
    expect(wrapper.find('#role-delete-role-1').exists()).toBe(false)
    expect(wrapper.text()).toContain('cannot be changed')

    // Non-system role: both controls exist.
    expect(wrapper.find('#role-edit-role-2').exists()).toBe(true)
    expect(wrapper.find('#role-delete-role-2').exists()).toBe(true)
  })

  it('shows a visible error and renders NO rows when the list fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 502,
          json: () => Promise.resolve({ error: 'bad_gateway', error_description: 'Could not reach the backend.' }),
        }),
      ),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Could not reach the backend.')
    expect(wrapper.findAll('[data-role-id]').length).toBe(0)
  })

  it('create: a name conflict lands on the name field, exactly one role="alert" in the DOM', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/roles' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({
                error: 'tenant_role.name_conflict',
                error_description: 'A role named BILLING_ADMIN already exists in this tenant.',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-role-name').setValue('BILLING_ADMIN')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const nameError = wrapper.find('#new-role-name-error')
    expect(nameError.exists()).toBe(true)
    expect(wrapper.find('#new-role-name').attributes('aria-invalid')).toBe('true')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('create: success closes the dialog state and refreshes the list', async () => {
    let listCallCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/roles' && init?.method === 'POST') {
          return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve(roleFixture({ id: 'new-role', isSystem: false })) })
        }
        if (url === '/t/acme/api/roles?page=0&size=20') {
          listCallCount += 1
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: listCallCount - 1, totalPages: 0 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-role-name').setValue('CUSTOM_ROLE')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(listCallCount).toBe(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('edit: a non-system role can be edited, sending both mutable fields together', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [roleFixture({ id: 'role-2', name: 'BILLING_ADMIN', isSystem: false, description: 'Old' })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/roles/role-2' && init?.method === 'PATCH') {
          const body = JSON.parse(String(init?.body))
          expect(body).toEqual({ description: 'New description', isDefault: true })
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(roleFixture({ id: 'role-2', name: 'BILLING_ADMIN', isSystem: false, description: 'New description', isDefault: true })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#role-edit-role-2').trigger('click')
    await flushPromises()
    await wrapper.find('#role-edit-description').setValue('New description')
    await wrapper.find('#role-edit-is-default').trigger('click')
    await wrapper.find('#role-edit-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('delete: 204 removes the role from the list', async () => {
    let deleted = false
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: deleted ? [] : [roleFixture({ id: 'role-2', name: 'BILLING_ADMIN', isSystem: false })],
                page: 0,
                size: 20,
                totalElements: deleted ? 0 : 1,
                totalPages: deleted ? 0 : 1,
              }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/roles/role-2' && init?.method === 'DELETE') {
          deleted = true
          return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createRolesRouter()
    const wrapper = mount(TenantRolesView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-role-id="role-2"]').exists()).toBe(true)
    await wrapper.find('#role-delete-role-2').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-role-id="role-2"]').exists()).toBe(false)
  })
})
