import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TenantProfileSettingsTab from './TenantProfileSettingsTab.vue'

// FE-5.6: entirely read-only. Tenant ID and sign-in URL are derived from the
// slug prop alone and must never depend on the entry-resolve call
// succeeding; display name/status degrade to "unavailable" on any non-ok
// result rather than treating it as fatal (entryResolve.ts's notFound arm
// deliberately can't distinguish unknown/suspended/deleted).
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantProfileSettingsTab', () => {
  it('renders the Tenant ID and sign-in URL from the slug alone, independent of the network', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('network down'))),
    )
    const wrapper = mount(TenantProfileSettingsTab, { props: { slug: 'ten_acme-inc' } })
    await flushPromises()

    expect(wrapper.find('#tenant-profile-id').text()).toBe('ten_acme-inc')
    expect(wrapper.find('#tenant-profile-sign-in-url').text()).toContain('/t/ten_acme-inc/login')
  })

  it('displays the display name and status on a successful resolve', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.startsWith('/api/entry/resolve')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                tenantId: 'ten_acme-inc',
                displayName: 'Acme Inc',
                status: 'ACTIVE',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const wrapper = mount(TenantProfileSettingsTab, { props: { slug: 'ten_acme-inc' } })
    await flushPromises()

    expect(wrapper.text()).toContain('Acme Inc')
    expect(wrapper.text()).toContain('ACTIVE')
  })

  it('a failed resolve degrades the display name to "unavailable" without hiding the Tenant ID or sign-in URL', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: false, status: 404 })),
    )
    const wrapper = mount(TenantProfileSettingsTab, { props: { slug: 'ten_acme-inc' } })
    await flushPromises()

    expect(wrapper.find('#tenant-profile-name-unavailable').exists()).toBe(true)
    expect(wrapper.find('#tenant-profile-id').text()).toBe('ten_acme-inc')
    expect(wrapper.find('#tenant-profile-sign-in-url').text()).toContain('/t/ten_acme-inc/login')
  })

  it('FE-6.1: Retry after a failed resolve recovers — unavailable no longer sticks forever', async () => {
    let shouldFail = true
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (!url.startsWith('/api/entry/resolve'))
          return Promise.reject(new Error(`unexpected fetch: ${url}`))
        if (shouldFail) return Promise.resolve({ ok: false, status: 404 })
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              tenantId: 'ten_acme-inc',
              displayName: 'Acme Inc',
              status: 'ACTIVE',
            }),
        })
      }),
    )
    const wrapper = mount(TenantProfileSettingsTab, { props: { slug: 'ten_acme-inc' } })
    await flushPromises()
    expect(wrapper.find('#tenant-profile-name-unavailable').exists()).toBe(true)

    shouldFail = false
    await wrapper.find('#tenant-profile-retry').trigger('click')
    await flushPromises()

    expect(wrapper.find('#tenant-profile-name-unavailable').exists()).toBe(false)
    expect(wrapper.text()).toContain('Acme Inc')
  })

  it('states the gap: no editable display name, no support-email field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: false, status: 404 })),
    )
    const wrapper = mount(TenantProfileSettingsTab, { props: { slug: 'ten_acme-inc' } })
    await flushPromises()

    const notice = wrapper.find('#tenant-profile-gap-notice').text()
    expect(notice.toLowerCase()).toContain('support-email')
  })
})
