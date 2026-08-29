import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import RegistrationSettingsTab from './RegistrationSettingsTab.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-5.4: carried over from Stage UI-3e's TenantSettingsView.spec.ts, now
// scoped to just the registration tab and adapted for the four RADIO CARDS
// (spec §6.4.7) replacing the old bare <select> — every mode's consequence
// is readable at once, not just the selected one.
function registrationConfigFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return { tenantId: 'tenant-1', mode: 'EMAIL_VERIFIED', ...overrides }
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearCsrfToken()
})

describe('RegistrationSettingsTab', () => {
  it('renders all four RegistrationMode literals as radio cards, each with its own consequence text', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(registrationConfigFixture()),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = mount(RegistrationSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    const values = wrapper.findAll('input[type="radio"]').map((i) => i.attributes('value'))
    expect(values.sort()).toEqual(
      ['ADMIN_APPROVED', 'EMAIL_VERIFIED', 'INVITE_ONLY', 'OPEN'].sort(),
    )
    expect(wrapper.text()).toContain('ACTIVE immediately')
    expect(wrapper.text()).toContain('invite')
  })

  it('the gap notice names both missing capabilities: no separate verification toggle, no per-tenant domain list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(registrationConfigFixture()),
        }),
      ),
    )
    const wrapper = mount(RegistrationSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    const notice = wrapper.find('#registration-gap-notice').text()
    expect(notice).toContain('not a separate toggle')
    expect(notice.toLowerCase()).toContain('domain')
  })

  it('save PUTs {mode}', async () => {
    const putBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/registration-config' && (!init || init.method === undefined)) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(registrationConfigFixture({ mode: 'OPEN' })),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
        }
        if (url === '/t/acme/api/registration-config' && init?.method === 'PUT') {
          putBodies.push(JSON.parse(String(init.body)))
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(registrationConfigFixture({ mode: 'ADMIN_APPROVED' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const wrapper = mount(RegistrationSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    await wrapper.find('#registration-mode-ADMIN_APPROVED').setValue()
    await wrapper.find('#registration-form').trigger('submit.prevent')
    await flushPromises()

    expect(putBodies).toEqual([{ mode: 'ADMIN_APPROVED' }])
  })

  it('emits update:dirty when the selected mode diverges from the loaded one, false again after save', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/registration-config' && (!init || init.method === undefined)) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(registrationConfigFixture({ mode: 'EMAIL_VERIFIED' })),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
        }
        if (url === '/t/acme/api/registration-config' && init?.method === 'PUT') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(registrationConfigFixture({ mode: 'OPEN' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const wrapper = mount(RegistrationSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()
    expect(wrapper.emitted('update:dirty')).toBeFalsy()

    await wrapper.find('#registration-mode-OPEN').setValue()
    await flushPromises()
    expect(wrapper.emitted('update:dirty')?.at(-1)).toEqual([true])

    await wrapper.find('#registration-form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.emitted('update:dirty')?.at(-1)).toEqual([false])
  })
})
