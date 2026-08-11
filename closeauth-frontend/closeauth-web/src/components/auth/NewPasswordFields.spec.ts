import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import NewPasswordFields from './NewPasswordFields.vue'

// Phase 4a: the shared "type a new password twice" form body extracted out
// of ResetPasswordView.vue so PasswordRotationView.vue can reuse it without
// a runtime mode branch. Covers what's genuinely new here vs. what
// ResetPasswordView.spec.ts already covered on the old inline markup: id
// prefixing (the regression guard that lets that spec pass unmodified),
// the opt-in min-length rule (off by default, on for rotation), and the
// emitted payload shape.

function mountFields(props: Partial<InstanceType<typeof NewPasswordFields>['$props']> = {}) {
  return mount(NewPasswordFields, {
    props: {
      idPrefix: 'test-prefix',
      submitLabel: 'Submit',
      submittingLabel: 'Submitting…',
      isSubmitting: false,
      ...props,
    },
  })
}

async function fillAndSubmit(wrapper: ReturnType<typeof mountFields>, password: string, confirmPassword = password) {
  await wrapper.find('#test-prefix-new').setValue(password)
  await wrapper.find('#test-prefix-confirm').setValue(confirmPassword)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

describe('NewPasswordFields', () => {
  it('ids are derived from idPrefix', () => {
    const wrapper = mountFields({ idPrefix: 'reset-password' })
    expect(wrapper.find('#reset-password-new').exists()).toBe(true)
    expect(wrapper.find('#reset-password-confirm').exists()).toBe(true)
  })

  it('emits submit with the password when both fields match and no min-length is set', async () => {
    const wrapper = mountFields()
    await fillAndSubmit(wrapper, 'anything')

    expect(wrapper.emitted('submit')).toEqual([['anything']])
  })

  it('blocks submission on mismatch and never emits', async () => {
    const wrapper = mountFields()
    await fillAndSubmit(wrapper, 'New-Correct-Pw-123!', 'Different-Pw-456!')

    expect(wrapper.emitted('submit')).toBeUndefined()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('do not match')
  })

  it('enforces minLength when set, blocking submission below it', async () => {
    const wrapper = mountFields({ minLength: 8 })
    await fillAndSubmit(wrapper, 'short')

    expect(wrapper.emitted('submit')).toBeUndefined()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('at least 8 characters')
  })

  it('allows a short password through when minLength is unset (default 0)', async () => {
    const wrapper = mountFields()
    await fillAndSubmit(wrapper, 'a')

    expect(wrapper.emitted('submit')).toEqual([['a']])
  })

  it('renders the bannerMessage prop as an alert', () => {
    const wrapper = mountFields({ bannerMessage: 'Something went wrong.' })
    const alerts = wrapper.findAll('[role="alert"]')
    expect(alerts.some((a) => a.text() === 'Something went wrong.')).toBe(true)
  })

  it('disables inputs and shows submittingLabel while isSubmitting', () => {
    const wrapper = mountFields({ isSubmitting: true, submittingLabel: 'Working…' })
    expect(wrapper.find('#test-prefix-new').attributes('disabled')).toBeDefined()
    expect(wrapper.find('#test-prefix-confirm').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[type="submit"]').text()).toBe('Working…')
  })

  it('ignores a submit attempt while already submitting', async () => {
    const wrapper = mountFields({ isSubmitting: true })
    await fillAndSubmit(wrapper, 'anything')

    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
