import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TenantBrandingProvider from './TenantBrandingProvider.vue'

function stubBrandingFetch(body: Record<string, string>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/branding')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.documentElement.dataset.theme = 'light'
  document.documentElement.style.cssText = ''
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantBrandingProvider', () => {
  it('scopes injected custom properties to its own root, never document.documentElement', async () => {
    stubBrandingFetch({
      logoUrl: '',
      primaryColor: '#123456',
      backgroundColor: '#ffffff',
      accentColor: '#654321',
      companyName: 'Acme',
    })

    const wrapper = mount(TenantBrandingProvider, {
      props: { clientId: 'admin-console-acme' },
      slots: { default: '<div>content</div>' },
    })
    await flushPromises()

    expect(wrapper.element.getAttribute('data-scope')).toBe('hosted')
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-primary')).toBe('#123456')
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-background')).toBe('#ffffff')
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-accent')).toBe('#654321')

    // The actual bug being fixed: nothing lands on the document root.
    expect(document.documentElement.style.getPropertyValue('--brand-primary')).toBe('')
    expect(document.documentElement.style.getPropertyValue('--brand-background')).toBe('')
    expect(document.documentElement.style.getPropertyValue('--brand-accent')).toBe('')
  })

  it('a malicious payload (javascript: logo, CSS-breakout colour) never reaches setProperty and never escapes the scope', async () => {
    stubBrandingFetch({
      logoUrl: 'javascript:alert(1)',
      primaryColor: 'red;}body{display:none',
      backgroundColor: '#ffffff;--ca-ink:red',
      accentColor: '',
      companyName: 'Evil Tenant',
    })

    const wrapper = mount(TenantBrandingProvider, {
      props: { clientId: 'admin-console-evil-1' },
      slots: { default: '<div>content</div>' },
    })
    await flushPromises()

    // Every field fails validation (colours aren't #RRGGBB/#RGB, logo isn't
    // https:) — none of it should ever reach setProperty.
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-primary')).toBe('')
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-primary-text')).toBe('')
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--brand-background')).toBe('')
    expect(document.documentElement.style.cssText).toBe('')
  })

  it('exposes hasLogo=false for an invalid logo URL via the slot', async () => {
    stubBrandingFetch({
      logoUrl: 'javascript:alert(1)',
      primaryColor: '',
      backgroundColor: '',
      accentColor: '',
      companyName: '',
    })

    let slotHasLogo: unknown
    mount(TenantBrandingProvider, {
      props: { clientId: 'admin-console-evil-2' },
      slots: {
        default: (scope: { hasLogo: boolean }) => {
          slotHasLogo = scope.hasLogo
          return []
        },
      },
    })
    await flushPromises()

    expect(slotHasLogo).toBe(false)
  })

  it('substitutes --ca-accent for --brand-primary-text when the brand colour fails contrast against the current canvas, but keeps the raw colour on --brand-primary', async () => {
    document.documentElement.dataset.theme = 'light'
    // Near-white — fails contrast against the light canvas (also near-white).
    stubBrandingFetch({
      logoUrl: '',
      primaryColor: '#fefefe',
      backgroundColor: '',
      accentColor: '',
      companyName: 'Acme',
    })

    const wrapper = mount(TenantBrandingProvider, {
      props: { clientId: 'admin-console-low-contrast' },
      slots: { default: '<div>content</div>' },
    })
    await flushPromises()

    const el = wrapper.element as HTMLElement
    expect(el.style.getPropertyValue('--brand-primary')).toBe('#fefefe')
    expect(el.style.getPropertyValue('--brand-primary-text')).toBe('var(--ca-accent)')
  })

  it('keeps a high-contrast brand colour as its own --brand-primary-text value', async () => {
    document.documentElement.dataset.theme = 'light'
    // Near-black — passes contrast against the light canvas easily.
    stubBrandingFetch({
      logoUrl: '',
      primaryColor: '#000000',
      backgroundColor: '',
      accentColor: '',
      companyName: 'Acme',
    })

    const wrapper = mount(TenantBrandingProvider, {
      props: { clientId: 'admin-console-acme-2' },
      slots: { default: '<div>content</div>' },
    })
    await flushPromises()

    const el = wrapper.element as HTMLElement
    expect(el.style.getPropertyValue('--brand-primary-text')).toBe('#000000')
  })
})
