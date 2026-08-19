import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from './EmptyState.vue'

describe('EmptyState', () => {
  it('renders the title and description', () => {
    const wrapper = mount(EmptyState, {
      props: { title: 'No tenants yet', description: 'Provision one to get started.' },
    })
    expect(wrapper.text()).toContain('No tenants yet')
    expect(wrapper.text()).toContain('Provision one to get started.')
  })

  it('renders the action slot when provided, omits it otherwise', () => {
    const withAction = mount(EmptyState, {
      props: { title: 'T', description: 'D' },
      slots: { action: '<button>Provision tenant</button>' },
    })
    expect(withAction.find('button').exists()).toBe(true)

    const withoutAction = mount(EmptyState, { props: { title: 'T', description: 'D' } })
    expect(withoutAction.find('button').exists()).toBe(false)
  })
})
