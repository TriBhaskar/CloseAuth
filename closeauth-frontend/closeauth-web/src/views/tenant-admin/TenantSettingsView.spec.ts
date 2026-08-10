import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantSettingsView from './TenantSettingsView.vue'
import { clearCsrfToken } from '@/api/client'

// Stage UI-3e: the settings page's required proofs — the empty-string
// logoUrl guard (the load-bearing rule this whole stage exists to enforce
// correctly, not rediscover badly), field-level 400s landing on the right
// control (both the bean-validation errors-map shape AND the
// errors-map-less domain-code shape branding.logo_url_not_https
// introduces), and the registration-mode <select> actually offering all
// four literals with a consequence panel that tracks the selection.
async function createSettingsRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/console/settings', name: 'tenant-admin-settings', component: TenantSettingsView }],
  })
  await router.push('/t/acme/console/settings')
  await router.isReady()
  return router
}

function brandingFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    logoUrl: '',
    primaryColor: '#4F46E5',
    backgroundColor: '#FFFFFF',
    accentColor: '#22D3EE',
    companyName: '',
    ...overrides,
  }
}

function registrationConfigFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return { tenantId: 'tenant-1', mode: 'EMAIL_VERIFIED', ...overrides }
}

beforeEach(() => {
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantSettingsView — branding', () => {
  it('an empty-string logoUrl renders NO <img> at all — the load-bearing empty-string guard', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture({ logoUrl: '' })) })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#branding-logo-preview').exists()).toBe(false)
  })

  it('a non-empty logoUrl renders the preview <img>', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ logoUrl: 'https://cdn.example.test/logo.png' })),
          })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    const img = wrapper.find('#branding-logo-preview')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://cdn.example.test/logo.png')
  })

  it('a bean-validation 400 (errors map) lands on the specific color field, not a generic banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture()) })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/branding' && init?.method === 'PUT') {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({
                error: 'validation.failed',
                error_description: 'Request validation failed',
                errors: { primaryColor: 'must be a #RRGGBB hex color' },
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#branding-primary-color').setValue('red')
    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#branding-primary-color-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('must be a #RRGGBB hex color')
    expect(wrapper.find('#branding-primary-color').attributes('aria-invalid')).toBe('true')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('branding.logo_url_not_https (a code-only 400, no errors map) lands on the logo field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture()) })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/branding' && init?.method === 'PUT') {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({
                error: 'branding.logo_url_not_https',
                error_description: 'logo_url must be an absolute https URL',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#branding-logo-url').setValue('http://insecure.example.test/logo.png')
    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#branding-logo-url-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('logo_url must be an absolute https URL')
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('save PUTs all five fields, seeded from the last GET (full replacement)', async () => {
    const putBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding' && (!init || init.method === undefined)) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ companyName: 'Acme' })),
          })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
        }
        if (url === '/t/acme/api/branding' && init?.method === 'PUT') {
          putBodies.push(JSON.parse(String(init.body)))
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ companyName: 'Acme Renamed' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()

    expect(putBodies).toHaveLength(1)
    expect(putBodies[0]).toEqual({
      logoUrl: '',
      primaryColor: '#4F46E5',
      backgroundColor: '#FFFFFF',
      accentColor: '#22D3EE',
      companyName: 'Acme Renamed',
    })
  })
})

describe('TenantSettingsView — registration mode', () => {
  it('the mode select offers all four RegistrationMode literals', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture()) })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    const values = wrapper.findAll('#registration-mode option').map((o) => o.attributes('value'))
    expect(values.sort()).toEqual(['ADMIN_APPROVED', 'EMAIL_VERIFIED', 'INVITE_ONLY', 'OPEN'].sort())
  })

  it('the consequence panel changes when a different mode is selected', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture()) })
        }
        if (url === '/t/acme/api/registration-config') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture({ mode: 'OPEN' })) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    const before = wrapper.find('#registration-mode-consequence').text()
    expect(before).toContain('ACTIVE immediately')

    await wrapper.find('#registration-mode').setValue('INVITE_ONLY')
    await flushPromises()

    const after = wrapper.find('#registration-mode-consequence').text()
    expect(after).toContain('invite')
    expect(after).not.toBe(before)
  })

  it('save PUTs {mode}', async () => {
    const putBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(brandingFixture()) })
        }
        if (url === '/t/acme/api/registration-config' && (!init || init.method === undefined)) {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(registrationConfigFixture({ mode: 'OPEN' })) })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
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

    const router = await createSettingsRouter()
    const wrapper = mount(TenantSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#registration-mode').setValue('ADMIN_APPROVED')
    await wrapper.find('#registration-form').trigger('submit.prevent')
    await flushPromises()

    expect(putBodies).toEqual([{ mode: 'ADMIN_APPROVED' }])
  })
})
