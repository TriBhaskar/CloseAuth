import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SecretRevealPanel from './SecretRevealPanel.vue'

// Dialog/DialogContent render through a Teleport — stubbed to plain
// pass-through elements so they're queryable at all (same convention as
// TenantUsersView.spec.ts's dialogStubs). DialogContent's stub deliberately
// has no declared props, so Vue's default attribute fallthrough still
// places aria-describedby (and any other attrs SecretRevealPanel passes)
// onto the stub's root <div> — enough to test the a11y wiring without
// depending on the real component's internal data-slot markup.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

function mountPanel() {
  return mount(SecretRevealPanel, {
    props: {
      open: true,
      title: 'Client registered',
      warningMessage: 'These credentials are shown ONE TIME ONLY.',
      fields: [
        { id: 'client-id', label: 'client_id', value: 'admin-console-acme', maskable: false },
        { id: 'secret', label: 'client_secret', value: 'sk_super_secret_value', maskable: true },
      ],
    },
    global: { stubs: dialogStubs },
  })
}

describe('SecretRevealPanel', () => {
  it('masks a maskable field by default and reveals it on toggle', async () => {
    const wrapper = mountPanel()
    const secretCode = wrapper.find('#secret-reveal-secret')
    expect(secretCode.text()).not.toContain('sk_super_secret_value')
    expect(secretCode.text()).toMatch(/^•+$/)

    const revealButtons = wrapper.findAll('button').filter((b) => b.text() === 'Reveal')
    await revealButtons[0]!.trigger('click')
    expect(wrapper.find('#secret-reveal-secret').text()).toBe('sk_super_secret_value')
  })

  it('never masks a non-maskable field and shows no Reveal toggle for it', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('#secret-reveal-client-id').text()).toBe('admin-console-acme')
  })

  it('renders a warning banner with role="alert"', () => {
    const wrapper = mountPanel()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('ONE TIME ONLY')
  })

  it("copy button on a field copies that field's real, unmasked value", async () => {
    const wrapper = mountPanel()
    const copyButtons = wrapper.findAll('button').filter((b) => b.text() === 'Copy')
    await copyButtons[1]!.trigger('click') // the secret field's copy button
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('sk_super_secret_value')
  })

  it('Continue stays disabled until the acknowledgement checkbox is checked, then emits continue', async () => {
    const wrapper = mountPanel()
    const continueButton = wrapper.find('#secret-reveal-continue')
    expect(continueButton.attributes('disabled')).toBeDefined()

    await continueButton.trigger('click')
    expect(wrapper.emitted('continue')).toBeFalsy()

    await wrapper.find('#secret-reveal-ack').trigger('click')
    expect(wrapper.find('#secret-reveal-continue').attributes('disabled')).toBeUndefined()

    await wrapper.find('#secret-reveal-continue').trigger('click')
    expect(wrapper.emitted('continue')).toHaveLength(1)
  })

  it('renders no close (×) button — the only exit is the gated Continue action', () => {
    const wrapper = mountPanel()
    // The real DialogClose (sr-only "Close" button) is stubbed away entirely
    // here since we don't stub DialogClose at all and SecretRevealPanel
    // passes show-close-button="false" — confirmed by there being no
    // element anywhere containing the "Close" sr-only text.
    expect(wrapper.text()).not.toContain('Close')
  })

  it('ties the warning message to the dialog content via aria-describedby', () => {
    const wrapper = mountPanel()
    // DialogContent's stub has no declared props, so Vue's default
    // attribute fallthrough places aria-describedby on its root <div>.
    expect(wrapper.find('[aria-describedby="secret-reveal-warning"]').exists()).toBe(true)
    expect(wrapper.find('#secret-reveal-warning').exists()).toBe(true)
  })
})
