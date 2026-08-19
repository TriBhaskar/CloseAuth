import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantAccountView from './TenantAccountView.vue'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'
import { clearCsrfToken } from '@/api/csrf'

// FE-4d: the self-service account page. reka-ui's TabsTrigger generates its
// own id (data-tab is the stable hook — FE-4a's established gotcha) and
// switches on @mousedown.left, not @click.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

async function createAccountRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/account', name: 'tenant-account', component: TenantAccountView }],
  })
  await router.push('/t/acme/account')
  await router.isReady()
  return router
}

function profileFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'user-1',
    tenantId: 'tenant-1',
    email: 'nonadmin@acme.test',
    emailVerified: true,
    phone: null,
    phoneVerified: false,
    firstName: 'Nadia',
    lastName: 'NonAdmin',
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    roles: [],
    isLastActiveAdmin: null,
    ...overrides,
  }
}

async function switchTab(wrapper: ReturnType<typeof mount>, tab: string): Promise<void> {
  await wrapper.find(`[data-tab="${tab}"]`).trigger('mousedown')
  await flushPromises()
}

function baseFetch(overrides: Record<string, () => Promise<unknown>> = {}) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/t/acme/api/me') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(profileFixture()) })
    }
    if (url === '/t/acme/api/me/sessions' && (!init || init.method === undefined)) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) })
    }
    if (url === '/api/csrf') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
    }
    const key = init?.method ? `${init.method} ${url}` : url
    const override = overrides[key] ?? overrides[url]
    if (override) return override()
    return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method ?? 'GET'}`))
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAccountView', () => {
  it('renders all three tabs', async () => {
    vi.stubGlobal('fetch', baseFetch())
    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('[data-tab="profile"]').exists()).toBe(true)
    expect(wrapper.find('[data-tab="password"]').exists()).toBe(true)
    expect(wrapper.find('[data-tab="sessions"]').exists()).toBe(true)
  })

  it('Profile tab shows read-only data, including a non-admin holding no roles', async () => {
    vi.stubGlobal('fetch', baseFetch())
    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#account-profile-email').text()).toBe('nonadmin@acme.test')
    expect(wrapper.text()).toContain('Nadia NonAdmin')
    expect(wrapper.text()).toContain('None')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('Password tab: rejects submit when the current password is empty', async () => {
    vi.stubGlobal('fetch', baseFetch())
    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'password')

    await wrapper.find('#account-password-new').setValue('new-Password123')
    await wrapper.find('#account-password-confirm').setValue('new-Password123')
    await wrapper.find('#account-password-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('#account-current-password').attributes('aria-invalid')).toBe('true')
  })

  it('Password tab: a wrong current password lands the field-specific error, not a banner', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch({
        'POST /t/acme/api/me/change-password': () =>
          Promise.resolve({
            ok: false,
            status: 403,
            json: () => Promise.resolve({ error: 'user.invalid_credentials', error_description: 'Invalid credentials' }),
          }),
      }),
    )
    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'password')

    await wrapper.find('#account-current-password').setValue('wrong-password')
    await wrapper.find('#account-password-new').setValue('new-Password123')
    await wrapper.find('#account-password-confirm').setValue('new-Password123')
    await wrapper.find('#account-password-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('Incorrect current password.')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('Password tab: success shows the signed-out-everywhere disclosure and Continue signs out', async () => {
    vi.stubGlobal(
      'fetch',
      baseFetch({
        'POST /t/acme/api/me/change-password': () => Promise.resolve({ ok: true, status: 204 }),
      }),
    )
    Object.defineProperty(window, 'location', { configurable: true, value: { assign: vi.fn() } })

    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    const store = useTenantAdminSessionStore()
    // A real navigation (via a prior guard run) would have already
    // populated slug — set it directly here so signOut() below isn't a
    // no-op (its own guard: `if (!slug.value) return`).
    store.slug = 'acme'
    await flushPromises()
    await switchTab(wrapper, 'password')

    await wrapper.find('#account-current-password').setValue('current-Password123')
    await wrapper.find('#account-password-new').setValue('new-Password123')
    await wrapper.find('#account-password-confirm').setValue('new-Password123')
    await wrapper.find('#account-password-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain("You've been signed out of every session")
    expect(window.location.assign).not.toHaveBeenCalled()

    await wrapper.find('#account-password-signout').trigger('click')
    expect(window.location.assign).toHaveBeenCalledWith('/t/acme/admin/logout')
  })

  it('Sessions tab: discloses that the current session cannot be identified', async () => {
    vi.stubGlobal('fetch', baseFetch())
    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'sessions')

    expect(wrapper.text()).toContain("can't yet identify which session")
  })

  it('Sessions tab: lists sessions and revokes one via ConfirmDialog', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/me') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(profileFixture()) })
        }
        if (url === '/t/acme/api/me/sessions' && init?.method === undefined) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve([
                {
                  id: 'session-1',
                  rememberMe: false,
                  ipAddress: '203.0.113.5',
                  userAgent: 'Mozilla/5.0 Test',
                  amr: 'pwd',
                  createdAt: '2026-01-01T00:00:00Z',
                  idleExpiresAt: '2026-01-01T01:00:00Z',
                  absoluteExpiresAt: '2026-01-02T00:00:00Z',
                  lastAccessedAt: '2026-01-01T00:30:00Z',
                },
              ]),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/me/sessions/session-1' && init?.method === 'DELETE') {
          return Promise.resolve({ ok: true, status: 204 })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method ?? 'GET'}`))
      }),
    )

    const router = await createAccountRouter()
    const wrapper = mount(TenantAccountView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()
    await switchTab(wrapper, 'sessions')

    expect(wrapper.find('[data-session-id="session-1"]').exists()).toBe(true)
    await wrapper.find('#account-revoke-session-session-1').trigger('click')
    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-session-id="session-1"]').exists()).toBe(false)
  })
})
