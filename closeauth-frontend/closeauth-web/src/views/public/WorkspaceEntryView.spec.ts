import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceEntryView from './WorkspaceEntryView.vue'

async function createEntryRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: WorkspaceEntryView },
      { path: '/t/:slug', component: { template: '<div />' } },
      { path: '/platform/login', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return router
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('WorkspaceEntryView', () => {
  it('idle: no remembered tenant — plain "Continue" button, no fetch on mount', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new Error('should not be called')))
    vi.stubGlobal('fetch', fetchMock)

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Continue')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('idle: a remembered tenant pre-fills the field and resolves its display name into the button', async () => {
    localStorage.setItem('closeauth.lastTenantId', 'ten_acme-inc')
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
        }),
      ),
    )

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })
    await flushPromises()

    expect((wrapper.find('#entry-tenant-id').element as HTMLInputElement).value).toBe('ten_acme-inc')
    expect(wrapper.text()).toContain('Continue to Acme Inc')
    expect(wrapper.text()).toContain('Use a different workspace')
  })

  it('"Use a different workspace" clears the field, the remembered state, and localStorage', async () => {
    localStorage.setItem('closeauth.lastTenantId', 'ten_acme-inc')
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
        }),
      ),
    )

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('button[type="button"]').trigger('click')

    expect((wrapper.find('#entry-tenant-id').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).not.toContain('Use a different workspace')
    expect(localStorage.getItem('closeauth.lastTenantId')).toBeNull()
  })

  it('normalises the field on blur (lowercase, missing ten_ prefix added)', async () => {
    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    const input = wrapper.find('#entry-tenant-id')
    await input.setValue('ACME-INC')
    await input.trigger('blur')

    expect((input.element as HTMLInputElement).value).toBe('ten_acme-inc')
  })

  it('a malformed tenant ID shows the client-side error and never calls resolve', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new Error('should not be called')))
    vi.stubGlobal('fetch', fetchMock)

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    await wrapper.find('#entry-tenant-id').setValue('!!')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain("That doesn't look like a tenant ID")
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('an unknown/suspended/rate-limited tenant (all collapsed to notFound) shows the same generic error', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) })))

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    await wrapper.find('#entry-tenant-id').setValue('ten_does-not-exist')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain("We couldn't find that workspace")
  })

  it('a network failure shows a distinct error, not the enumeration-safe "not found" copy', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))))

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    await wrapper.find('#entry-tenant-id').setValue('ten_acme-inc')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Something went wrong')
    expect(wrapper.text()).not.toContain("We couldn't find that workspace")
  })

  it('a resolved tenant navigates to /t/{tenantId} and remembers it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ tenantId: 'ten_acme-inc', displayName: 'Acme Inc', status: 'ACTIVE' }),
        }),
      ),
    )

    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    await wrapper.find('#entry-tenant-id').setValue('ten_acme-inc')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc')
    expect(localStorage.getItem('closeauth.lastTenantId')).toBe('ten_acme-inc')
  })

  it('links to /platform/login', async () => {
    const router = await createEntryRouter()
    const wrapper = mount(WorkspaceEntryView, { global: { plugins: [router] } })

    const link = wrapper.findAll('a').find((a) => a.text().includes('Platform administrator'))
    expect(link?.attributes('href')).toBe('/platform/login')
  })
})
