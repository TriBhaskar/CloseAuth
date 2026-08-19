import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantUsersView from './TenantUsersView.vue'
import { clearCsrfToken } from '@/api/csrf'
import { useToast } from '@/composables/useToast'

// FE-4a: rebuilt onto DataTable with real server-side status/role/q
// filtering and two create modes (invitation, temporary password) replacing
// the old single admin-typed-password form. The required proofs from the
// UI-3b era still hold (real paging drives requests, a failed load shows a
// visible alert and renders NO rows, a 400 validation-errors map lands on
// the specific field) — this file updates their fixtures/URLs/selectors for
// the new shape rather than re-litigating what they already proved.
//
// The Dialog primitives (reka-ui, portal + focus-trap based) are stubbed to
// plain pass-through wrappers so the create-user form is always present in
// the DOM regardless of open/closed animation state — these tests are about
// OUR form logic, not reka-ui's own open/close behavior.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

// SecretRevealPanel's own warning banner (role="alert", id="secret-reveal-
// warning") is unconditionally rendered by this view's template (the panel
// component itself gates visibility on its `open` prop via <Dialog>, but
// dialogStubs above replaces Dialog/DialogContent/DialogDescription with
// plain pass-through divs, so its content — including that permanent alert
// — is always in the DOM regardless of `open`). "No alert" / "exactly one
// alert" assertions below must exclude it, or they'd fail for a reason that
// has nothing to do with what each test actually checks.
function otherAlerts(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('[role="alert"]').filter((a) => a.attributes('id') !== 'secret-reveal-warning')
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
    roles: [],
    isLastActiveAdmin: null,
    ...overrides,
  }
}

function emptyRoleCatalog() {
  return { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }
}

beforeEach(() => {
  clearCsrfToken()
  // useToast's `toasts` list is module-level shared state — clear it so a
  // toast asserted in one test can't leak into the next.
  useToast().toasts.value.splice(0)
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
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-user-id="user-1"]').text()).toContain('alice@acme.test')
    expect(wrapper.find('[data-user-id="user-2"]').text()).toContain('bob@acme.test')
    expect(otherAlerts(wrapper).length).toBe(0)
  })

  it('shows a visible error and renders NO rows when the list fails — never fabricated data', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
        return Promise.resolve({
          ok: false,
          status: 502,
          json: () => Promise.resolve({ error: 'bad_gateway', error_description: 'Could not reach the backend.' }),
        })
      }),
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
      if (url === '/t/acme/api/roles?page=0&size=100') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
      }
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

  it('status filter: selecting a status re-fetches with the filter and resets to page 0', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === '/t/acme/api/roles?page=0&size=100') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#users-filter-status').setValue('SUSPENDED')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/t/acme/api/users?page=0&size=20&status=SUSPENDED', expect.anything())
  })

  it('roles column shows the first two role names plus a +n overflow indicator', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
        if (url === '/t/acme/api/users?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [userFixture({ roles: ['TENANT_ADMIN', 'BILLING_ADMIN', 'TENANT_MEMBER'] })],
                page: 0,
                size: 20,
                totalElements: 1,
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

    const row = wrapper.find('[data-user-id="user-1"]')
    expect(row.text()).toContain('TENANT_ADMIN')
    expect(row.text()).toContain('BILLING_ADMIN')
    expect(row.text()).not.toContain('TENANT_MEMBER')
    expect(row.text()).toContain('+1')
  })

  it('create user (invite mode, default): a 400 validation-errors map lands on the specific field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
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
        if (url === '/t/acme/api/invites' && init?.method === 'POST') {
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
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#new-user-email-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('must be a well-formed email address')
    expect(wrapper.find('#new-user-email').attributes('aria-invalid')).toBe('true')
    // No generic banner alongside the field-level error.
    expect(otherAlerts(wrapper).length).toBe(1)
  })

  it('create user (invite mode): success closes the dialog and toasts, without opening SecretRevealPanel', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
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
        if (url === '/t/acme/api/invites' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () =>
              Promise.resolve({ id: 'invite-1', email: 'new@acme.test', expiresAt: '2026-01-08T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createUsersRouter()
    const wrapper = mount(TenantUsersView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-user-email').setValue('new@acme.test')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(otherAlerts(wrapper).length).toBe(0)
    const { toasts } = useToast()
    expect(toasts.value.some((t) => t.title === 'Invitation sent to new@acme.test')).toBe(true)
    // No secret was ever produced by this mode — nothing to reveal.
    expect(wrapper.text()).not.toContain('Temporary password')
  })

  it('create user (temporary-password mode): success refreshes the list and reveals the password exactly once', async () => {
    let listCallCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/roles?page=0&size=100') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyRoleCatalog()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/users/with-temp-credential' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () =>
              Promise.resolve({
                user: userFixture({ id: 'new-user', email: 'new@acme.test' }),
                temporaryPassword: 'generated-temp-pw',
                temporaryPasswordExpiresAt: '2026-01-08T00:00:00Z',
              }),
          })
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

    await wrapper.find('#new-user-mode-temp-password').setValue()
    await wrapper.find('#new-user-email').setValue('new@acme.test')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // Re-fetched the list after a successful create (side effect, not just no error shown).
    expect(listCallCount).toBe(2)
    expect(wrapper.text()).toContain('Temporary password')
    expect(wrapper.text()).toContain('Expires')
    // Masked by default (§5: "show once... masked by default") — the raw
    // value isn't in the DOM at all until Reveal is clicked.
    expect(wrapper.text()).not.toContain('generated-temp-pw')
    expect(wrapper.find('#secret-reveal-temp-password').text()).toMatch(/^•+$/)

    const revealButton = wrapper.findAll('button').find((b) => b.text() === 'Reveal')
    expect(revealButton).toBeTruthy()
    await revealButton!.trigger('click')
    expect(wrapper.find('#secret-reveal-temp-password').text()).toBe('generated-temp-pw')
  })
})
