import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ScopeSelector from './ScopeSelector.vue'
import type { ScopeCatalogGroup } from '@/api/tenantAdminScopeCatalog'

function scope(id: string, scopeName: string, description: string | null = null) {
  return {
    id,
    resourceServerId: 'rs-1',
    scopeName,
    description,
    isDefault: false,
    requiresConsent: true,
    createdAt: '2026-01-01T00:00:00Z',
    usedByRoleCount: null,
  }
}

describe('ScopeSelector', () => {
  it('renders one checkbox per scope, keyed by slug:scopeName', () => {
    const groups: ScopeCatalogGroup[] = [
      {
        resourceServerId: 'rs-1',
        resourceServerName: 'Billing API',
        resourceServerSlug: 'billing-api',
        scopes: [scope('s-1', 'read'), scope('s-2', 'write')],
      },
    ]
    const wrapper = mount(ScopeSelector, { props: { groups, modelValue: new Set<string>() } })

    expect(wrapper.find('#scope-selector-billing-api\\:read').exists()).toBe(true)
    expect(wrapper.find('#scope-selector-billing-api\\:write').exists()).toBe(true)
    expect(wrapper.text()).toContain('billing-api:read')
  })

  it('does not render a group header when there is only one group', () => {
    const groups: ScopeCatalogGroup[] = [
      { resourceServerId: 'rs-1', resourceServerName: 'Billing API', resourceServerSlug: 'billing-api', scopes: [scope('s-1', 'read')] },
    ]
    const wrapper = mount(ScopeSelector, { props: { groups, modelValue: new Set<string>() } })
    expect(wrapper.text()).not.toContain('Billing API')
  })

  it('renders a group header per resource server when there are multiple', () => {
    const groups: ScopeCatalogGroup[] = [
      { resourceServerId: 'rs-1', resourceServerName: 'Billing API', resourceServerSlug: 'billing-api', scopes: [scope('s-1', 'read')] },
      { resourceServerId: 'rs-2', resourceServerName: 'Orders API', resourceServerSlug: 'orders-api', scopes: [scope('s-2', 'write')] },
    ]
    const wrapper = mount(ScopeSelector, { props: { groups, modelValue: new Set<string>() } })
    expect(wrapper.text()).toContain('Billing API')
    expect(wrapper.text()).toContain('Orders API')
  })

  it('toggling a checkbox emits an updated Set with the key added', async () => {
    const groups: ScopeCatalogGroup[] = [
      { resourceServerId: 'rs-1', resourceServerName: 'Billing API', resourceServerSlug: 'billing-api', scopes: [scope('s-1', 'read')] },
    ]
    const wrapper = mount(ScopeSelector, { props: { groups, modelValue: new Set<string>() } })

    await wrapper.find('#scope-selector-billing-api\\:read').trigger('click')

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const lastValue = emitted?.[emitted.length - 1]?.[0] as Set<string>
    expect(lastValue.has('billing-api:read')).toBe(true)
  })

  it('toggling an already-held key emits a Set with it removed', async () => {
    const groups: ScopeCatalogGroup[] = [
      { resourceServerId: 'rs-1', resourceServerName: 'Billing API', resourceServerSlug: 'billing-api', scopes: [scope('s-1', 'read')] },
    ]
    const wrapper = mount(ScopeSelector, { props: { groups, modelValue: new Set(['billing-api:read']) } })

    await wrapper.find('#scope-selector-billing-api\\:read').trigger('click')

    const emitted = wrapper.emitted('update:modelValue')
    const lastValue = emitted?.[0]?.[0] as Set<string>
    expect(lastValue.has('billing-api:read')).toBe(false)
  })

  it('a pending key stays disabled and does not emit on click', async () => {
    const groups: ScopeCatalogGroup[] = [
      { resourceServerId: 'rs-1', resourceServerName: 'Billing API', resourceServerSlug: 'billing-api', scopes: [scope('s-1', 'read')] },
    ]
    const wrapper = mount(ScopeSelector, {
      props: { groups, modelValue: new Set<string>(), pendingKeys: new Set(['billing-api:read']) },
    })

    const checkbox = wrapper.find('#scope-selector-billing-api\\:read')
    expect(checkbox.attributes('disabled')).toBeDefined()
  })

  it('an empty catalog states plainly there is nothing to select', () => {
    const wrapper = mount(ScopeSelector, { props: { groups: [], modelValue: new Set<string>() } })
    expect(wrapper.text()).toContain('nothing to select')
  })
})
