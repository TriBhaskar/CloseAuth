import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorState from './ErrorState.vue'

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

describe('ErrorState', () => {
  it('renders the message and has an alert role', () => {
    const wrapper = mount(ErrorState, { props: { message: 'Could not reach the backend.' } })
    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.text()).toContain('Could not reach the backend.')
  })

  it('does not render a trace_id chip when traceId is absent', () => {
    const wrapper = mount(ErrorState, { props: { message: 'Something went wrong.' } })
    expect(wrapper.text()).not.toContain('trace_id')
  })

  it('renders the trace_id chip only when traceId is present', () => {
    const wrapper = mount(ErrorState, { props: { message: 'Something went wrong.', traceId: 'tr-abc123' } })
    expect(wrapper.text()).toContain('trace_id')
    expect(wrapper.text()).toContain('tr-abc123')
  })

  it('emits retry when the retry action is clicked', async () => {
    const wrapper = mount(ErrorState, { props: { message: 'Failed.' } })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
