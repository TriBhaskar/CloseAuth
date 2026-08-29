import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BrandingSettingsTab from './BrandingSettingsTab.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-5.3: carried over from Stage UI-3e's TenantSettingsView.spec.ts, now
// scoped to just the branding tab — the empty-string logoUrl guard (the
// load-bearing rule this stage exists to enforce correctly, not rediscover
// badly), field-level 400s landing on the right control (both the
// bean-validation errors-map shape AND the errors-map-less domain-code shape
// branding.logo_url_not_https introduces), and the full-replacement PUT.
// New this session: the live preview mounts, and editing marks the tab dirty.
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

beforeEach(() => {
  setActivePinia(createPinia())
  clearCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('BrandingSettingsTab', () => {
  it('an empty-string logoUrl renders NO <img> at all — the load-bearing empty-string guard', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ logoUrl: '' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
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
            json: () =>
              Promise.resolve(brandingFixture({ logoUrl: 'https://cdn.example.test/logo.png' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
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
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture()),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
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

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    await wrapper.find('#branding-primary-color').setValue('red')
    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#branding-primary-color-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('must be a #RRGGBB hex color')
    expect(wrapper.find('#branding-primary-color').attributes('aria-invalid')).toBe('true')
  })

  it('branding.logo_url_not_https (a code-only 400, no errors map) lands on the logo field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding' && (!init || init.method === undefined)) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture()),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
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

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    await wrapper.find('#branding-logo-url').setValue('http://insecure.example.test/logo.png')
    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#branding-logo-url-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe('logo_url must be an absolute https URL')
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
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
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

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
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

  it('renders a live preview that reflects the form (FE-5.3)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/branding') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ companyName: 'Acme' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()

    expect(wrapper.find('#branding-login-preview').text()).toContain('Acme')

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await flushPromises()

    expect(wrapper.find('#branding-login-preview').text()).toContain('Acme Renamed')
  })

  it('emits update:dirty when the form diverges from the loaded snapshot, and false again once saved', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url === '/t/acme/api/branding' && (!init || init.method === undefined)) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture()),
          })
        }
        if (url === '/api/csrf') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ token: 'csrf-token' }),
          })
        }
        if (url === '/t/acme/api/branding' && init?.method === 'PUT') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(brandingFixture({ companyName: 'Acme Renamed' })),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url} ${init?.method}`))
      }),
    )

    const wrapper = mount(BrandingSettingsTab, { props: { slug: 'acme' } })
    await flushPromises()
    expect(wrapper.emitted('update:dirty')).toBeFalsy()

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await flushPromises()
    expect(wrapper.emitted('update:dirty')?.at(-1)).toEqual([true])

    await wrapper.find('#branding-form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.emitted('update:dirty')?.at(-1)).toEqual([false])
  })
})
