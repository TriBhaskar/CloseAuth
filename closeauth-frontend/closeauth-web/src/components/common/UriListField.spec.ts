import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UriListField from './UriListField.vue'

// Client update/delete: extracted from CreateClientDialog.vue so the create
// wizard and the new client edit form share one repeatable-URI-row editor.
// idPrefix reproduces the exact ids CreateClientDialog.vue's own spec already
// asserts on (client-wizard-redirect-0/-add/-remove-0 etc.) — this spec
// covers the component in isolation; CreateClientDialog.spec.ts still proves
// those exact ids didn't move when it switched to this component.
const baseProps = {
  idPrefix: 'test-uri',
  label: 'Redirect URIs',
  placeholder: 'https://app.example.com/callback',
  addLabel: 'Add redirect URI',
  errors: {},
}

describe('UriListField', () => {
  it('renders one input per row, ids derived from idPrefix', () => {
    const wrapper = mount(UriListField, {
      props: { ...baseProps, modelValue: ['https://a.example.com', 'https://b.example.com'] },
    })
    expect(wrapper.find('#test-uri-0').exists()).toBe(true)
    expect(wrapper.find('#test-uri-1').exists()).toBe(true)
  })

  it('hides the remove button when only one row exists, shows it otherwise', () => {
    const single = mount(UriListField, { props: { ...baseProps, modelValue: [''] } })
    expect(single.find('#test-uri-remove-0').exists()).toBe(false)

    const multiple = mount(UriListField, { props: { ...baseProps, modelValue: ['', ''] } })
    expect(multiple.find('#test-uri-remove-0').exists()).toBe(true)
    expect(multiple.find('#test-uri-remove-1').exists()).toBe(true)
  })

  it('Add emits an appended row; Remove emits the row spliced out', async () => {
    const wrapper = mount(UriListField, { props: { ...baseProps, modelValue: ['https://a.example.com'] } })

    await wrapper.find('#test-uri-add').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['https://a.example.com', '']])

    const two = mount(UriListField, {
      props: { ...baseProps, modelValue: ['https://a.example.com', 'https://b.example.com'] },
    })
    await two.find('#test-uri-remove-0').trigger('click')
    expect(two.emitted('update:modelValue')?.[0]).toEqual([['https://b.example.com']])
  })

  it('renders a per-row error and the group error', () => {
    const wrapper = mount(UriListField, {
      props: {
        ...baseProps,
        modelValue: ['not-a-uri'],
        errors: { 0: 'Must be an absolute URI.' },
        groupError: 'At least one redirect URI is required for this client type.',
      },
    })
    expect(wrapper.text()).toContain('Must be an absolute URI.')
    expect(wrapper.text()).toContain('At least one redirect URI is required for this client type.')
  })
})
