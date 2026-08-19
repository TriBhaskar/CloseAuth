import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import JsonViewer from './JsonViewer.vue'

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

describe('JsonViewer', () => {
  it('is collapsed by default — the payload is not in the DOM', () => {
    const wrapper = mount(JsonViewer, { props: { value: { a: 1, secret: 'x' } } })
    expect(wrapper.find('pre').exists()).toBe(false)
    expect(wrapper.text()).toContain('Expand')
  })

  it('expands to show the formatted JSON on click', async () => {
    const wrapper = mount(JsonViewer, { props: { value: { a: 1 } } })
    await wrapper.find('button').trigger('click')
    expect(wrapper.find('pre').exists()).toBe(true)
    expect(wrapper.find('pre').text()).toContain('"a": 1')
    expect(wrapper.text()).toContain('Collapse')
  })

  it('copy-all copies the full formatted JSON, not just the visible text', async () => {
    const wrapper = mount(JsonViewer, { props: { value: { a: 1, b: 'two' } } })
    const buttons = wrapper.findAll('button')
    const copyButton = buttons[buttons.length - 1]!
    await copyButton.trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(JSON.stringify({ a: 1, b: 'two' }, null, 2))
  })
})
