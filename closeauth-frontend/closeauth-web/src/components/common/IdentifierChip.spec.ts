import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import IdentifierChip, { type ChipKind } from './IdentifierChip.vue'

const UUID = 'a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c'

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('IdentifierChip', () => {
  it('middle-truncates a long value, never tail-truncates', () => {
    const wrapper = mount(IdentifierChip, { props: { value: UUID, kind: 'user' } })
    const text = wrapper.text()
    expect(text).toContain('…')
    expect(text).not.toContain(UUID)
    // Head and tail are both present around the ellipsis.
    expect(text).toContain(UUID.slice(0, 8))
    expect(text).toContain(UUID.slice(-4))
  })

  it('does not truncate a short value', () => {
    const wrapper = mount(IdentifierChip, { props: { value: 'orders:read', kind: 'scope' } })
    expect(wrapper.text()).toContain('orders:read')
  })

  it('sets the full value as title and a kind-naming aria-label', () => {
    const wrapper = mount(IdentifierChip, { props: { value: UUID, kind: 'user' } })
    const valueEl = wrapper.find(`[title="${UUID}"]`)
    expect(valueEl.exists()).toBe(true)
    expect(valueEl.attributes('aria-label')).toBe(`user ID ${UUID}`)
  })

  it('applies a distinct kind-tint class per kind', () => {
    const kinds: Array<[ChipKind, string]> = [
      ['tenant', 'bg-chip-tenant'],
      ['user', 'bg-chip-user'],
      ['client', 'bg-chip-client'],
      ['resource-server', 'bg-chip-resource-server'],
      ['scope', 'bg-chip-scope'],
      ['session', 'bg-chip-session'],
      ['role', 'bg-chip-role'],
    ]
    for (const [kind, expectedClass] of kinds) {
      const wrapper = mount(IdentifierChip, { props: { value: 'v', kind } })
      expect(wrapper.find(`.${expectedClass}`).exists(), `expected ${expectedClass} for kind=${kind}`).toBe(true)
    }
  })

  it('renders a plain span when no href is given', () => {
    const wrapper = mount(IdentifierChip, { props: { value: 'v', kind: 'role' } })
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('renders a RouterLink when href is given', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/roles/:id', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(IdentifierChip, {
      props: { value: 'role-1', kind: 'role', href: '/roles/role-1' },
      global: { plugins: [router] },
    })
    const link = wrapper.find('a')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/roles/role-1')
  })

  it('clicking the copy control copies the full untruncated value', async () => {
    const wrapper = mount(IdentifierChip, { props: { value: UUID, kind: 'session' } })
    await wrapper.find('button').trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(UUID)
  })
})
