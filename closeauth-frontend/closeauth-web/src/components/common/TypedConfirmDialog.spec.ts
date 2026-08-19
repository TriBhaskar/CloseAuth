import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TypedConfirmDialog from './TypedConfirmDialog.vue'

// Dialog/DialogContent render through a Teleport (a portal to
// document.body in the real DOM) — @vue/test-utils' wrapper.find() only
// searches the component's own subtree, so real dialogs need stubbing to
// plain pass-through elements to be queryable at all. Same convention this
// codebase already established (see TenantUsersView.spec.ts's dialogStubs)
// — these tests are about TypedConfirmDialog's own logic, not reka-ui's
// portal/focus-trap behaviour (exercised elsewhere).
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

describe('TypedConfirmDialog', () => {
  it('keeps the confirm button disabled until the typed value matches matchText exactly', async () => {
    const wrapper = mount(TypedConfirmDialog, {
      props: { open: true, title: 'Delete tenant?', description: 'This is permanent.', matchText: 'ten_acme-inc' },
      global: { stubs: dialogStubs },
    })
    const confirmButton = wrapper.find('#typed-confirm-dialog-confirm')
    expect(confirmButton.attributes('disabled')).toBeDefined()

    const input = wrapper.find('#typed-confirm-input')
    await input.setValue('ten_acme')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()

    await input.setValue('ten_acme-inc')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeUndefined()
  })

  it('is case-sensitive', async () => {
    const wrapper = mount(TypedConfirmDialog, {
      props: { open: true, title: 'Delete tenant?', description: 'This is permanent.', matchText: 'ten_acme-inc' },
      global: { stubs: dialogStubs },
    })
    await wrapper.find('#typed-confirm-input').setValue('TEN_ACME-INC')
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()
  })

  it('emits confirm only when the button is enabled', async () => {
    const wrapper = mount(TypedConfirmDialog, {
      props: { open: true, title: 'Delete tenant?', description: 'This is permanent.', matchText: 'ten_acme-inc' },
      global: { stubs: dialogStubs },
    })
    await wrapper.find('#typed-confirm-input').setValue('ten_acme-inc')
    await wrapper.find('#typed-confirm-dialog-confirm').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('clears the typed value when the dialog closes and reopens', async () => {
    const wrapper = mount(TypedConfirmDialog, {
      props: { open: true, title: 'Delete tenant?', description: 'This is permanent.', matchText: 'ten_acme-inc' },
      global: { stubs: dialogStubs },
    })
    await wrapper.find('#typed-confirm-input').setValue('ten_acme-inc')
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect((wrapper.find('#typed-confirm-input').element as HTMLInputElement).value).toBe('')
  })

  it('disables both buttons while pending', () => {
    const wrapper = mount(TypedConfirmDialog, {
      props: {
        open: true,
        title: 'Delete tenant?',
        description: 'This is permanent.',
        matchText: 'ten_acme-inc',
        pending: true,
      },
      global: { stubs: dialogStubs },
    })
    expect(wrapper.find('#typed-confirm-dialog-confirm').attributes('disabled')).toBeDefined()
    expect(wrapper.find('#typed-confirm-input').attributes('disabled')).toBeDefined()
  })
})
