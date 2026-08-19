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

  // FE-2c: bare mode (RegisterView.vue) — no <form>/submit button of its
  // own; the caller drives validation via the exposed validate().
  describe('bare mode', () => {
    it('renders no form and no submit button', () => {
      const wrapper = mountFields({ bare: true })
      expect(wrapper.find('form').exists()).toBe(false)
      expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
    })

    it('validate() returns the password and sets no field errors on a valid match', async () => {
      const wrapper = mountFields({ bare: true, minLength: 8 })
      await wrapper.find('#test-prefix-new').setValue('New-Correct-Pw-123!')
      await wrapper.find('#test-prefix-confirm').setValue('New-Correct-Pw-123!')

      const result = wrapper.vm.validate()

      expect(result).toBe('New-Correct-Pw-123!')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    })

    it('validate() returns null and shows the mismatch error, exactly like form mode', async () => {
      const wrapper = mountFields({ bare: true })
      await wrapper.find('#test-prefix-new').setValue('New-Correct-Pw-123!')
      await wrapper.find('#test-prefix-confirm').setValue('Different-Pw-456!')

      const result = wrapper.vm.validate()
      await flushPromises()

      expect(result).toBeNull()
      const alert = wrapper.find('[role="alert"]')
      expect(alert.exists()).toBe(true)
      expect(alert.text()).toContain('do not match')
    })

    it('validate() returns null and shows the min-length error, exactly like form mode', async () => {
      const wrapper = mountFields({ bare: true, minLength: 8 })
      await wrapper.find('#test-prefix-new').setValue('short')
      await wrapper.find('#test-prefix-confirm').setValue('short')

      const result = wrapper.vm.validate()
      await flushPromises()

      expect(result).toBeNull()
      expect(wrapper.find('[role="alert"]').text()).toContain('at least 8 characters')
    })

    it('validate() returns null while isSubmitting, mirroring handleSubmit\'s own guard', () => {
      const wrapper = mountFields({ bare: true, isSubmitting: true })
      expect(wrapper.vm.validate()).toBeNull()
    })
  })

  // FE-2c: the live checklist — off by default (PasswordRotationView and
  // ResetPasswordView never opt in, so their rendered output is unchanged).
  describe('showChecklist', () => {
    it('renders nothing extra when showChecklist is false (the default)', () => {
      const wrapper = mountFields({ minLength: 8 })
      expect(wrapper.find('ul').exists()).toBe(false)
    })

    it('shows unmet items for an empty form, and updates reactively as the user types', async () => {
      const wrapper = mountFields({ showChecklist: true, minLength: 8 })
      const items = wrapper.findAll('li')
      expect(items).toHaveLength(2)
      expect(items[0]!.text()).toContain('At least 8 characters')
      expect(items[1]!.text()).toContain('Passwords match')
      // Neither met yet — unchecked marker.
      expect(items[0]!.text()).toContain('○')
      expect(items[1]!.text()).toContain('○')

      await wrapper.find('#test-prefix-new').setValue('New-Correct-Pw-123!')
      await wrapper.find('#test-prefix-confirm').setValue('New-Correct-Pw-123!')

      const updated = wrapper.findAll('li')
      expect(updated[0]!.text()).toContain('✓')
      expect(updated[1]!.text()).toContain('✓')
    })

    it('falls back to 8 characters in the copy when minLength is unset', () => {
      const wrapper = mountFields({ showChecklist: true })
      expect(wrapper.find('li').text()).toContain('At least 8 characters')
    })

    it('does not mark "passwords match" true from two empty fields', () => {
      const wrapper = mountFields({ showChecklist: true })
      const matchItem = wrapper.findAll('li')[1]!
      expect(matchItem.text()).toContain('○')
    })
  })
})
