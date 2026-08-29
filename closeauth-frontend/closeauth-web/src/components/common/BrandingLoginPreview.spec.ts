import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BrandingLoginPreview from './BrandingLoginPreview.vue'
import type { BrandingView } from '@/api/tenantAdminBranding'

function brandingFixture(overrides: Partial<BrandingView> = {}): BrandingView {
  return {
    logoUrl: '',
    primaryColor: '#4F46E5',
    backgroundColor: '#FFFFFF',
    accentColor: '#22D3EE',
    companyName: 'Acme',
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.documentElement.dataset.theme = 'light'
})

describe('BrandingLoginPreview', () => {
  it('renders the company name and applies the background colour', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ companyName: 'Acme Inc' }) },
    })
    expect(wrapper.text()).toContain('Acme Inc')
    const root = wrapper.find('#branding-login-preview').element as HTMLElement
    expect(root.style.backgroundColor).not.toBe('')
  })

  it('an empty-string logoUrl renders no <img> — the same guard the hosted pages use', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ logoUrl: '' }) },
    })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('a non-https logoUrl (invalid) renders no <img>', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ logoUrl: 'http://insecure.example.test/logo.png' }) },
    })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('a valid https logoUrl renders the <img>', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ logoUrl: 'https://cdn.example.test/logo.png' }) },
    })
    expect(wrapper.find('img').attributes('src')).toBe('https://cdn.example.test/logo.png')
  })

  it('a low-contrast primary colour previews the Sign in button with the accent fallback for TEXT, keeping the raw colour as the fill', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ primaryColor: '#fefefe' }) },
    })
    const button = wrapper.find('button').element as HTMLElement
    expect(button.style.backgroundColor).not.toBe('')
    expect(button.style.color).toBe('var(--ca-accent)')
  })

  it('a high-contrast primary colour keeps its own colour as the button text', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ primaryColor: '#000000' }) },
    })
    const button = wrapper.find('button').element as HTMLElement
    expect(button.style.color).toBe('rgb(0, 0, 0)')
  })

  it('an invalid hex colour never reaches a style binding', () => {
    const wrapper = mount(BrandingLoginPreview, {
      props: { branding: brandingFixture({ primaryColor: 'red;}body{display:none' }) },
    })
    const button = wrapper.find('button').element as HTMLElement
    expect(button.style.backgroundColor).toBe('')
  })
})
