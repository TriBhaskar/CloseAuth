import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import FormField from './FormField.vue'

// Stage UI-3b: promotes RegisterView.vue's hand-rolled label+input+alert
// markup into this component — these tests cover both halves of the
// contract: the label/alert rendering, and the scoped-slot values a
// consumer binds onto its own input (hasError/describedBy).

describe('FormField', () => {
  it('no error: no alert, and the slot receives hasError=false / describedBy=undefined', () => {
    const wrapper = mount(FormField, {
      props: { id: 'test-field', label: 'Test field' },
      slots: {
        default: `<template #default="{ hasError, describedBy }">
          <input id="test-field" :aria-invalid="hasError" :aria-describedby="describedBy" />
        </template>`,
      },
    })

    expect(wrapper.find('label').text()).toBe('Test field')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('input').attributes('aria-invalid')).toBe('false')
    expect(wrapper.find('input').attributes('aria-describedby')).toBeUndefined()
  })

  it('error set: renders role=alert with the message, and the slot receives hasError=true / a matching describedBy id', () => {
    const wrapper = mount(FormField, {
      props: { id: 'test-field', label: 'Test field', error: 'This field is required.' },
      slots: {
        default: `<template #default="{ hasError, describedBy }">
          <input id="test-field" :aria-invalid="hasError" :aria-describedby="describedBy" />
        </template>`,
      },
    })

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toBe('This field is required.')
    expect(alert.attributes('id')).toBe('test-field-error')
    expect(wrapper.find('input').attributes('aria-invalid')).toBe('true')
    expect(wrapper.find('input').attributes('aria-describedby')).toBe('test-field-error')
  })

  it('hint shown only when there is no error', () => {
    const wrapper = mount(FormField, {
      props: { id: 'test-field', label: 'Test field', hint: 'Optional detail.' },
    })
    expect(wrapper.text()).toContain('Optional detail.')

    const withError = mount(FormField, {
      props: { id: 'test-field', label: 'Test field', hint: 'Optional detail.', error: 'Bad value.' },
    })
    expect(withError.text()).not.toContain('Optional detail.')
    expect(withError.text()).toContain('Bad value.')
  })
})
