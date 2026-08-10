import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import QueryState from './QueryState.vue'

// Stage UI-3b: QueryState is the structural enforcement of the standing
// no-fake-data rule (src/stores/admin.ts) — these tests exist to prove the
// default slot genuinely never mounts alongside loading/error states, not
// just that the right text appears.

describe('QueryState', () => {
  it('loading: shows the loading placeholder, never the default slot', () => {
    const wrapper = mount(QueryState, {
      props: { loading: true },
      slots: { default: '<div id="real-content">real data</div>' },
    })

    expect(wrapper.find('#real-content').exists()).toBe(false)
    expect(wrapper.find('[aria-busy="true"]').exists()).toBe(true)
  })

  it('error: shows a role=alert with the message, never the default slot', () => {
    const wrapper = mount(QueryState, {
      props: { loading: false, error: 'Could not reach the backend.' },
      slots: { default: '<div id="real-content">real data</div>' },
    })

    expect(wrapper.find('#real-content').exists()).toBe(false)
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toBe('Could not reach the backend.')
  })

  it('neither loading nor error: renders the default slot, no alert', () => {
    const wrapper = mount(QueryState, {
      props: { loading: false, error: null },
      slots: { default: '<div id="real-content">real data</div>' },
    })

    expect(wrapper.find('#real-content').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
