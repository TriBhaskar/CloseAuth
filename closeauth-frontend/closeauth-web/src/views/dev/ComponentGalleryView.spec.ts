import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ComponentGalleryView from './ComponentGalleryView.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  })
})

async function mountGallery() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/t/:slug/console/roles/:roleId', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(ComponentGalleryView, { global: { plugins: [router] } })
}

describe('ComponentGalleryView', () => {
  it('mounts without throwing and renders every section', async () => {
    const wrapper = await mountGallery()
    const text = wrapper.text()
    expect(text).toContain('IdentifierChip')
    expect(text).toContain('StateBadge')
    expect(text).toContain('EmptyState')
    expect(text).toContain('ErrorState')
    expect(text).toContain('RelativeTime')
    expect(text).toContain('CopyButton')
    expect(text).toContain('JsonViewer')
    expect(text).toContain('FormField')
    expect(text).toContain('DataTable')
    expect(text).toContain('Dialog family')
    expect(text).toContain('Shells')
  })

  it('DataTable state buttons switch between loading/error/empty/loaded', async () => {
    const wrapper = await mountGallery()
    const buttons = wrapper.findAll('button').filter((b) => ['loading', 'error', 'loaded'].includes(b.text()))

    await buttons.find((b) => b.text() === 'loading')!.trigger('click')
    expect(wrapper.find('.skeleton').exists()).toBe(true)

    await buttons.find((b) => b.text() === 'error')!.trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)

    await buttons.find((b) => b.text() === 'loaded')!.trigger('click')
    expect(wrapper.text()).toContain('Ada Lovelace')
  })

  it('FormField error toggle shows and hides the inline error', async () => {
    const wrapper = await mountGallery()
    const toggle = wrapper.findAll('button').find((b) => b.text() === 'Toggle error')!
    expect(wrapper.text()).not.toContain('already taken')
    await toggle.trigger('click')
    expect(wrapper.text()).toContain('already taken')
  })

  it('renders one IdentifierChip per kind', async () => {
    const wrapper = await mountGallery()
    for (const value of [
      'ten_acme-inc-7f3a',
      'admin-console-acme',
      'orders:write',
      'sess_8f3a2b1c9d0e',
      'role_tenant-admin',
    ]) {
      expect(wrapper.text()).toContain(value.length > 12 ? value.slice(0, 8) : value)
    }
  })

  it('renders StateBadge for every tone including muted (no-fill)', async () => {
    const wrapper = await mountGallery()
    expect(wrapper.findAll('.bg-transparent.border-line-strong').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.bg-warn-wash').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.bg-ok-wash').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.bg-danger-wash').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.bg-accent-wash').length).toBeGreaterThan(0)
  })

  it('the theme toggle cycles the store mode', async () => {
    const wrapper = await mountGallery()
    const button = wrapper.find('button')
    const before = wrapper.text()
    await button.trigger('click')
    expect(wrapper.text()).not.toBe(before)
  })
})
