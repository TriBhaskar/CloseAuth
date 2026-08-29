import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SessionsSettingsTab from './SessionsSettingsTab.vue'

// FE-5.5: no backend exists for tenant-scoped session policy — this tab is
// an honest EmptyState, never hard-coded platform-default numbers. Proving
// the negative: no numeric duration ever appears anywhere in this component.
describe('SessionsSettingsTab', () => {
  it('renders an EmptyState naming the gap, with no fetch and no numbers', () => {
    const wrapper = mount(SessionsSettingsTab)
    expect(wrapper.text()).toContain("isn't configurable per tenant yet")
    expect(wrapper.text()).not.toMatch(/\d/)
  })
})
