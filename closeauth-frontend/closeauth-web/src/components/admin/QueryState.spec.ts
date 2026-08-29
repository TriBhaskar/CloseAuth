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
    expect(alert.text()).toContain('Could not reach the backend.')
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

// FE-6.1: the default error fallback used to be a dead-end <p role="alert">
// with no way to recover from a transient failure. It's now ErrorState, so
// every QueryState-based detail screen gets a working Retry for free unless
// it explicitly says the failure isn't retryable.
describe('QueryState — retry (FE-6.1)', () => {
  it('the default error fallback renders a working Retry action and emits retry up', async () => {
    const wrapper = mount(QueryState, {
      props: { loading: false, error: 'Could not reach the backend.' },
    })
    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('retryable=false suppresses the action — a 403 must not offer a retry', () => {
    const wrapper = mount(QueryState, {
      props: { loading: false, error: "You don't have access to this.", retryable: false },
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('a caller-overridden #error slot is unaffected by the retryable default', () => {
    const wrapper = mount(QueryState, {
      props: { loading: false, error: 'Failed.' },
      slots: { error: '<p id="custom-error">custom</p>' },
    })
    expect(wrapper.find('#custom-error').exists()).toBe(true)
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
