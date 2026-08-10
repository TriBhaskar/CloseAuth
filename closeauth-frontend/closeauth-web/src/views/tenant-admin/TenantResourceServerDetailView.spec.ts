import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantResourceServerDetailView from './TenantResourceServerDetailView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3c: audience is read-only TEXT, never an editable input
// (immutable after creation); no delete control renders for an
// autoCreated RS (replaced by an explanation); and a scope edit always
// submits all three mutable fields together, matching the backend's
// full-replacement PATCH semantics (updateScope sets description/isDefault/
// requiresConsent unconditionally — never a sparse merge).
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
    ...overrides,
  }
}

const emptyScopesPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

function scopeFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'scope-1',
    resourceServerId: 'rs-1',
    scopeName: 'read',
    description: 'Read access',
    isDefault: true,
    requiresConsent: false,
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

describe('TenantResourceServerDetailView', () => {
  it('audience is rendered as read-only text, not an input', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
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
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture({ autoCreated: false })) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
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
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture({ autoCreated: true })) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyScopesPage) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createDetailRouter()
    const wrapper = mount(TenantResourceServerDetailView, { global: { plugins: [router], stubs: dialogStubs } })
    await flushPromises()

    expect(wrapper.find('#rs-delete-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('created automatically with its client and cannot be deleted directly')
  })

  it('scope edit submits all three mutable fields together (full replacement, never a sparse patch)', async () => {
    const patchBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [scopeFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes/scope-1' && init?.method === 'PATCH') {
          patchBodies.push(JSON.parse(String(init.body)))
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve(scopeFixture({ description: 'Updated', isDefault: false, requiresConsent: true })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
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
      vi.fn((url: string) => {
        if (url === '/t/acme/api/resource-servers/rs-1') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(rsFixture()) })
        }
        if (url === '/t/acme/api/resource-servers/rs-1/scopes?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ items: [scopeFixture()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
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
})
