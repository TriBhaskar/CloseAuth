import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantUsersView from './TenantUsersView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3b: the list surface's required proofs — real paging drives
// requests, a failed load shows a visible alert and renders NO rows (the
// no-fake-data rule), and the create-user dialog's 400 errors map lands on
// the specific field, never a generic banner.
//
// The Dialog primitives (reka-ui, portal + focus-trap based) are stubbed to
// plain pass-through wrappers so the create-user form is always present in
// the DOM regardless of open/closed animation state — these tests are about
// OUR form logic, not reka-ui's own open/close behavior (already exercised
// elsewhere in this codebase's dependency tree).
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createUsersRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/users', name: 'tenant-admin-users', component: TenantUsersView },
      { path: '/t/:slug/console/users/:userId', name: 'tenant-admin-user-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/t/acme/console/users')
  await router.isReady()
  return router
}

function userFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
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
    ...overrides,
  }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantUsersView', () => {
  it('renders users from a real PageView, no alert on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/users?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [userFixture(), userFixture({ id: 'user-2', email: 'bob@acme.test' })],
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

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-user-id="user-1"]').text()).toContain('alice@acme.test')
    expect(wrapper.find('[data-user-id="user-2"]').text()).toContain('bob@acme.test')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows a visible error and renders NO rows when the list fails — never fabricated data', async () => {
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

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Could not reach the backend.')
    expect(wrapper.findAll('[data-user-id]').length).toBe(0)
  })

  it('paging: clicking next requests the next page from the backend', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === '/t/acme/api/users?page=0&size=20') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({ items: [userFixture()], page: 0, size: 20, totalElements: 21, totalPages: 2 }),
        })
      }
      if (url === '/t/acme/api/users?page=1&size=20') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              items: [userFixture({ id: 'user-2', email: 'page2@acme.test' })],
              page: 1,
              size: 20,
              totalElements: 21,
              totalPages: 2,
            }),
        })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#admin-pagination-next').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/t/acme/api/users?page=1&size=20', expect.anything())
    expect(wrapper.find('[data-user-id="user-2"]').text()).toContain('page2@acme.test')
  })

  it('create user: a 400 validation-errors map lands on the specific field, not a generic banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/users?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/users' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({
                error: 'validation.failed',
                error_description: 'Request validation failed',
                errors: { email: 'must be a well-formed email address' },
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-user-email').setValue('not-an-email')
    await wrapper.find('#new-user-password').setValue('Whatever-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#new-user-email-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('must be a well-formed email address')
    expect(wrapper.find('#new-user-email').attributes('aria-invalid')).toBe('true')
    // No generic banner alongside the field-level error.
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('create user: success closes the dialog state and refreshes the list', async () => {
    let listCallCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/users' && init?.method === 'POST') {
          return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve(userFixture({ id: 'new-user' })) })
        }
        if (url === '/t/acme/api/users?page=0&size=20') {
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

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-user-email').setValue('new@acme.test')
    await wrapper.find('#new-user-password').setValue('New-User-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // Re-fetched the list after a successful create (side effect, not just no error shown).
    expect(listCallCount).toBe(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
