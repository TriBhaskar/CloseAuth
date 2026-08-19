import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from './ConfirmDialog.vue'

// FE-1.8: ConfirmDialog had no dedicated spec before this session's move
// from components/admin/ — added now for the same reason TypedConfirmDialog
// and SecretRevealPanel got fresh specs: confidence after a relocation, not
// a rewrite. Dialog stubs per the codebase's established convention (see
// TenantUsersView.spec.ts) — Teleport-rendered content isn't in the
// wrapper's own subtree.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

describe('ConfirmDialog', () => {
  it('renders the caller-supplied title and description', () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: 'Suspend Acme Inc?', description: 'Active tokens stop working immediately.' },
      global: { stubs: dialogStubs },
    })
    expect(wrapper.text()).toContain('Suspend Acme Inc?')
    expect(wrapper.text()).toContain('Active tokens stop working immediately.')
  })

  it('Cancel emits update:open(false); the confirm button emits confirm', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: 'T', description: 'D' },
      global: { stubs: dialogStubs },
    })
    await wrapper.find('button:not(#confirm-dialog-confirm)').trigger('click')
    expect(wrapper.emitted('update:open')).toEqual([[false]])

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('disables both buttons while pending, and shows "Working…" on the confirm button', () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: 'T', description: 'D', pending: true },
      global: { stubs: dialogStubs },
    })
    const buttons = wrapper.findAll('button')
    expect(buttons.every((b) => b.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.find('#confirm-dialog-confirm').text()).toBe('Working…')
  })

  it('defaults to a destructive confirm button; non-destructive is opt-in', () => {
    const destructive = mount(ConfirmDialog, {
      props: { open: true, title: 'T', description: 'D' },
      global: { stubs: dialogStubs },
    })
    expect(destructive.find('#confirm-dialog-confirm').classes().join(' ')).toContain('bg-destructive')

    const nonDestructive = mount(ConfirmDialog, {
      props: { open: true, title: 'T', description: 'D', destructive: false },
      global: { stubs: dialogStubs },
    })
    expect(nonDestructive.find('#confirm-dialog-confirm').classes().join(' ')).not.toContain('bg-destructive')
  })
})
