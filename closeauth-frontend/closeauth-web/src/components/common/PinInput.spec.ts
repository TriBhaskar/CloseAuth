import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PinInput from './PinInput.vue'

describe('PinInput', () => {
  it('renders 6 boxes by default, each with a distinct aria-label', () => {
    const wrapper = mount(PinInput, { props: { modelValue: '' } })
    const boxes = wrapper.findAll('input')
    expect(boxes).toHaveLength(6)
    expect(boxes[0]!.attributes('aria-label')).toBe('Digit 1 of 6')
    expect(boxes[5]!.attributes('aria-label')).toBe('Digit 6 of 6')
  })

  it('renders a custom length', () => {
    const wrapper = mount(PinInput, { props: { modelValue: '', length: 4 } })
    expect(wrapper.findAll('input')).toHaveLength(4)
  })

  it('wraps the boxes in an accessible group', () => {
    const wrapper = mount(PinInput, { props: { modelValue: '' } })
    const group = wrapper.find('[role="group"]')
    expect(group.exists()).toBe(true)
    expect(group.attributes('aria-label')).toBe('Verification code')
  })

  it('pre-fills from an initial modelValue', () => {
    const wrapper = mount(PinInput, { props: { modelValue: '12' } })
    const boxes = wrapper.findAll('input')
    expect((boxes[0]!.element as HTMLInputElement).value).toBe('1')
    expect((boxes[1]!.element as HTMLInputElement).value).toBe('2')
    expect((boxes[2]!.element as HTMLInputElement).value).toBe('')
  })

  it('typing a digit auto-advances focus to the next box and emits the combined value', async () => {
    let emitted = ''
    const wrapper = mount(PinInput, {
      props: { modelValue: '', 'onUpdate:modelValue': (v: string) => (emitted = v) },
      attachTo: document.body,
    })
    const boxes = wrapper.findAll('input')

    await boxes[0]!.setValue('1')
    await boxes[0]!.trigger('input')

    expect(document.activeElement).toBe(boxes[1]!.element)
    expect(emitted).toBe('1')
    wrapper.unmount()
  })

  it('typing the last digit does not try to advance past the final box', async () => {
    let emitted = ''
    const wrapper = mount(PinInput, {
      props: { modelValue: '12345', 'onUpdate:modelValue': (v: string) => (emitted = v) },
      attachTo: document.body,
    })
    const boxes = wrapper.findAll('input')

    // No error thrown attempting to focus a box past the end.
    await boxes[5]!.setValue('6')
    await boxes[5]!.trigger('input')

    expect(emitted).toBe('123456')
    wrapper.unmount()
  })

  it('backspace on an empty box moves focus back and clears the previous box', async () => {
    const wrapper = mount(PinInput, { props: { modelValue: '12' }, attachTo: document.body })
    const boxes = wrapper.findAll('input')

    await boxes[2]!.trigger('keydown', { key: 'Backspace' })

    expect(document.activeElement).toBe(boxes[1]!.element)
    expect((boxes[1]!.element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('backspace on the first box (nothing before it) does nothing special', async () => {
    const wrapper = mount(PinInput, { props: { modelValue: '' }, attachTo: document.body })
    const boxes = wrapper.findAll('input')

    await boxes[0]!.trigger('keydown', { key: 'Backspace' })

    // No throw, no crash — nothing to move back to.
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })

  it('pasting a full code distributes it across all boxes and emits the combined value', async () => {
    let emitted = ''
    const wrapper = mount(PinInput, {
      props: { modelValue: '', 'onUpdate:modelValue': (v: string) => (emitted = v) },
    })
    const boxes = wrapper.findAll('input')

    await boxes[0]!.trigger('paste', { clipboardData: { getData: () => '123456' } })

    expect(emitted).toBe('123456')
    const updated = wrapper.findAll('input')
    expect((updated[5]!.element as HTMLInputElement).value).toBe('6')
  })

  it('pasting strips non-digit characters and truncates to the configured length', async () => {
    let emitted = ''
    const wrapper = mount(PinInput, {
      props: { modelValue: '', length: 4, 'onUpdate:modelValue': (v: string) => (emitted = v) },
    })
    const boxes = wrapper.findAll('input')

    await boxes[0]!.trigger('paste', { clipboardData: { getData: () => '1a2-b3c4d5' } })

    expect(emitted).toBe('1234')
  })

  it('disables every box when disabled is true', () => {
    const wrapper = mount(PinInput, { props: { modelValue: '', disabled: true } })
    for (const box of wrapper.findAll('input')) {
      expect(box.attributes('disabled')).toBeDefined()
    }
  })
})
