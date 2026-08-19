import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import RelativeTime from './RelativeTime.vue'

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-08-15T12:00:00.000Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('RelativeTime', () => {
  it('renders a relative label for a recent past timestamp', () => {
    const wrapper = mount(RelativeTime, { props: { value: '2026-08-15T10:00:00.000Z' } })
    expect(wrapper.text().toLowerCase()).toContain('hour')
    expect(wrapper.text()).toContain('ago')
  })

  it('renders the absolute UTC ISO in the title attribute', () => {
    const wrapper = mount(RelativeTime, { props: { value: '2026-08-15T10:00:00.000Z' } })
    expect(wrapper.attributes('title')).toBe('2026-08-15T10:00:00.000Z')
    expect(wrapper.attributes('datetime')).toBe('2026-08-15T10:00:00.000Z')
  })

  it('applies the mono class', () => {
    const wrapper = mount(RelativeTime, { props: { value: '2026-08-15T10:00:00.000Z' } })
    expect(wrapper.classes()).toContain('font-mono')
  })

  it('falls back to rendering the raw value for an invalid date', () => {
    const wrapper = mount(RelativeTime, { props: { value: 'not-a-date' } })
    expect(wrapper.text()).toBe('not-a-date')
  })
})
