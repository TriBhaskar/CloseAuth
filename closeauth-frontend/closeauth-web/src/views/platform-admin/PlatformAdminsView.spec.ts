import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformAdminsView from './PlatformAdminsView.vue'
import { clearCsrfToken } from '@/api/csrf'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

// Stage UI-4 / FE-3c: the platform-admins surface's required proofs — a
// zero-role admin renders the "cannot sign in yet" state, a 409
// platform_admin.last_admin renders its own specific message (never a
// generic conflict banner), role assign/revoke is a deliberate Save-gated
// second step (never a checkbox mutating on contact), Activate is now
// confirmed like Suspend, and the self-lockout guard — both the UI
// (disabled Suspend/PLATFORM_ADMIN-checkbox on your own row) and the
// backend's independent refusal surfacing its own specific copy.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogTrigger: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createAdminsRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/platform/console/admins', name: 'platform-admin-admins', component: PlatformAdminsView }],
  })
  await router.push('/platform/console/admins')
  await router.isReady()
  return router
}

function adminFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'admin-1',
    email: 'staff@closeauth.test',
    status: 'ACTIVE',
    firstName: 'Staff',
    lastName: 'One',
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

let pinia: ReturnType<typeof createPinia>

beforeEach(() => {
  clearCsrfToken()
  pinia = createPinia()
  setActivePinia(pinia)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** Signs the store in as `adminId` — drives the self-lockout guard's "is this my own row" check. */
function signInAs(adminId: string): void {
  usePlatformAdminSessionStore().state = {
    kind: 'active',
    adminId,
    email: 'staff@closeauth.test',
    roles: ['PLATFORM_ADMIN'],
    accessTokenExpiresAt: '2026-01-01T00:05:00Z',
  }
}

async function mountView(router: Awaited<ReturnType<typeof createAdminsRouter>>) {
  const wrapper = mount(PlatformAdminsView, { global: { plugins: [router, pinia], stubs: dialogStubs } })
  await flushPromises()
  return wrapper
}

describe('PlatformAdminsView', () => {
  it('a zero-role admin renders the "cannot sign in yet" state', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    expect(wrapper.text()).toContain('No roles — cannot sign in yet')
  })

  it('an admin holding PLATFORM_ADMIN shows it in the Roles column, not the "cannot sign in" copy', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_ADMIN']) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    expect(wrapper.text()).not.toContain('cannot sign in yet')
    expect(wrapper.text()).toContain('PLATFORM_ADMIN')
  })

  it('platform_admin.last_admin renders its specific message on suspend, not a generic conflict banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_ADMIN']) })
        }
        if (url === '/platform/api/admins/admin-1/suspend' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false,
            status: 409,
            json: () =>
              Promise.resolve({ error: 'platform_admin.last_admin', error_description: 'Cannot remove the last active PLATFORM_ADMIN' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#admin-action-suspend-admin-1').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('last active PLATFORM_ADMIN')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
  })

  it('activate now requires confirmation too (previously fired immediately)', async () => {
    let status = 'SUSPENDED'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [adminFixture({ status })], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
        }
        if (url === '/platform/api/admins/admin-1/activate' && init?.method === 'POST') {
          status = 'ACTIVE'
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(adminFixture({ status })) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#admin-action-activate-admin-1').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Activate staff@closeauth.test?')
    expect(wrapper.find('[data-admin-id="admin-1"]').text()).toContain('SUSPENDED') // not yet applied

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-admin-id="admin-1"]').text()).toContain('ACTIVE')
  })

  it('creating a platform admin succeeds and names that it holds no roles yet', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          })
        }
        if (url === '/platform/api/admins' && init?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 201,
            json: () => Promise.resolve(adminFixture({ email: 'new@closeauth.test' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#new-platform-admin-email').setValue('new@closeauth.test')
    await wrapper.find('#new-platform-admin-password').setValue('correct horse battery staple')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const banner = wrapper.find('[role="status"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('holds no platform roles yet')
    expect(banner.text()).toContain('cannot sign in')
  })

  // ---- FE-3c: role assignment as a deliberate, Save-gated second step -----

  it('Manage roles: toggling a checkbox does not call the API until Save is clicked', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === '/platform/api/admins?page=0&size=20') {
        return Promise.resolve({
          ok: true, status: 200,
          json: () => Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
        })
      }
      if (url === '/platform/api/admins/admin-1/roles') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#admin-manage-roles-admin-1').trigger('click')
    await flushPromises()
    const callsBeforeToggle = fetchMock.mock.calls.length

    // Checkbox is a reka-ui button-role="checkbox", not a native input — click, not setValue.
    await wrapper.find('#roles-dialog-PLATFORM_ADMIN').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls.length).toBe(callsBeforeToggle) // no network call from the toggle alone
  })

  it('Manage roles: Save diffs against the roles the dialog opened with and calls assign/revoke only for what changed', async () => {
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_SUPPORT']) })
        }
        if (url === '/platform/api/admins/admin-1/roles/PLATFORM_ADMIN' && init?.method === 'POST') {
          calls.push('assign:PLATFORM_ADMIN')
          return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
        }
        if (url === '/platform/api/admins/admin-1/roles/PLATFORM_SUPPORT' && init?.method === 'DELETE') {
          calls.push('revoke:PLATFORM_SUPPORT')
          return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(undefined) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#admin-manage-roles-admin-1').trigger('click')
    await flushPromises()
    // Starts with PLATFORM_SUPPORT checked; flip both: check PLATFORM_ADMIN, uncheck PLATFORM_SUPPORT.
    // Checkbox is a reka-ui button-role="checkbox", not a native input — click, not setValue.
    await wrapper.find('#roles-dialog-PLATFORM_ADMIN').trigger('click')
    await wrapper.find('#roles-dialog-PLATFORM_SUPPORT').trigger('click')
    await wrapper.find('#roles-dialog-save').trigger('click')
    await flushPromises()

    expect(calls.sort()).toEqual(['assign:PLATFORM_ADMIN', 'revoke:PLATFORM_SUPPORT'])
  })

  // ---- FE-3c: self-lockout guard ------------------------------------------

  it('your own row: Suspend is disabled, and the PLATFORM_ADMIN checkbox is disabled in Manage roles', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_ADMIN']) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    signInAs('admin-1')

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    const suspendButton = wrapper.find('#admin-action-suspend-admin-1')
    expect(suspendButton.attributes('disabled')).toBeDefined()

    await wrapper.find('#admin-manage-roles-admin-1').trigger('click')
    await flushPromises()
    expect(wrapper.find('#roles-dialog-PLATFORM_ADMIN').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain("You can't remove your own PLATFORM_ADMIN role.")
  })

  it("someone else's row is unaffected by the self-lockout guard", async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_ADMIN']) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    signInAs('some-other-admin-id')

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    expect(wrapper.find('#admin-action-suspend-admin-1').attributes('disabled')).toBeUndefined()

    await wrapper.find('#admin-manage-roles-admin-1').trigger('click')
    await flushPromises()
    expect(wrapper.find('#roles-dialog-PLATFORM_ADMIN').attributes('disabled')).toBeUndefined()
  })

  it('if the backend independently refuses a self-targeting suspend (a race the UI guard normally prevents), the specific copy still surfaces', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/platform/api/admins?page=0&size=20') {
          return Promise.resolve({
            ok: true, status: 200,
            json: () => Promise.resolve({ items: [adminFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/platform/api/admins/admin-1/roles') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(['PLATFORM_ADMIN']) })
        }
        if (url === '/platform/api/admins/admin-1/suspend' && init?.method === 'POST') {
          return Promise.resolve({
            ok: false, status: 403,
            json: () => Promise.resolve({ error: 'platform_admin.self_action_refused', error_description: 'refused' }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    // Deliberately NOT signed in as admin-1 — this proves the copy-mapping logic itself is
    // correct independent of the (separately tested) UI guard that normally prevents reaching it.

    const router = await createAdminsRouter()
    const wrapper = await mountView(router)

    await wrapper.find('#admin-action-suspend-admin-1').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain("You can't do that to your own account.")
  })
})
