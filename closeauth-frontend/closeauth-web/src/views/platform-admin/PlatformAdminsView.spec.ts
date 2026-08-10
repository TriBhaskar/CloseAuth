import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformAdminsView from './PlatformAdminsView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-4: the platform-admins surface's required proofs — a zero-role
// admin renders the "cannot sign in yet" state (the roles read this stage
// added, GET /admins/{id}/roles, is what makes this visible at all), and a
// 409 platform_admin.last_admin renders its own specific message, never a
// generic conflict banner.
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

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

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
    const wrapper = mount(PlatformAdminsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('No roles — cannot sign in yet')
  })

  it('an admin holding PLATFORM_ADMIN renders it checked', async () => {
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
    const wrapper = mount(PlatformAdminsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('cannot sign in yet')
    const checkbox = wrapper.find('#role-admin-1-PLATFORM_ADMIN')
    expect(checkbox.exists()).toBe(true)
    expect(checkbox.attributes('data-state') ?? checkbox.attributes('aria-checked')).toBeTruthy()
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
    const wrapper = mount(PlatformAdminsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#admin-action-suspend-admin-1').trigger('click')
    await flushPromises()
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('last active PLATFORM_ADMIN')
    expect(wrapper.text()).not.toContain('This action conflicts with the current state.')
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
    const wrapper = mount(PlatformAdminsView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    await wrapper.find('#new-platform-admin-email').setValue('new@closeauth.test')
    await wrapper.find('#new-platform-admin-password').setValue('correct horse battery staple')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const banner = wrapper.find('[role="status"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('holds no platform roles yet')
    expect(banner.text()).toContain('cannot sign in')
  })
})
