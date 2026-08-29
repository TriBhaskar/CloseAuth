import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import AuditEventDrawer from './AuditEventDrawer.vue'
import type { AuditEventView } from '@/api/tenantAdminAudit'

// Sheet/SheetContent render through a Teleport (a portal to document.body in
// the real DOM) — @vue/test-utils' wrapper.find() only searches the
// component's own subtree, so the sheet primitives are stubbed to plain
// pass-through elements, the same convention TypedConfirmDialog.spec.ts
// already established for Dialog.
const sheetStubs = {
  Sheet: { template: '<div><slot /></div>' },
  SheetContent: { template: '<div><slot /></div>' },
  SheetHeader: { template: '<div><slot /></div>' },
  SheetTitle: { template: '<div><slot /></div>' },
  SheetDescription: { template: '<div><slot /></div>' },
}

function eventFixture(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'event-1',
    eventType: 'USER_CREATED',
    outcome: 'SUCCESS',
    tenantId: 'tenant-1',
    subjectUserId: 'user-1',
    actorUserId: 'admin-1',
    createdAt: '2026-01-01T00:00:00Z',
    eventData: { email: 'new@acme.test', status: 'ACTIVE' },
    ...overrides,
  }
}

describe('AuditEventDrawer', () => {
  it('renders nothing when event is null, even if open is true', () => {
    const wrapper = mount(AuditEventDrawer, {
      props: { open: true, event: null },
      global: { stubs: sheetStubs },
    })
    expect(wrapper.find('#audit-event-drawer').exists()).toBe(false)
  })

  it('renders the event type, outcome, and subject as a chip', () => {
    const wrapper = mount(AuditEventDrawer, {
      props: { open: true, event: eventFixture() },
      global: { stubs: sheetStubs },
    })
    expect(wrapper.text()).toContain('USER_CREATED')
    expect(wrapper.text()).toContain('SUCCESS')
    expect(wrapper.text()).toContain('user-1')
  })

  it('an admin-driven event (actorUserId absent, actorPlatformAdminId present) renders "CloseAuth platform admin", never "unknown user"', () => {
    const wrapper = mount(AuditEventDrawer, {
      props: {
        open: true,
        event: eventFixture({ actorUserId: undefined, actorPlatformAdminId: 'platform-admin-1' }),
      },
      global: { stubs: sheetStubs },
    })
    expect(wrapper.text()).toContain('CloseAuth platform admin')
    expect(wrapper.text().toLowerCase()).not.toContain('unknown')
  })

  it('eventData renders as formatted JSON once JsonViewer is expanded', async () => {
    const wrapper = mount(AuditEventDrawer, {
      props: { open: true, event: eventFixture() },
      global: { stubs: sheetStubs },
    })

    const expandButton = wrapper.findAll('button').find((b) => b.text() === 'Expand')
    expect(expandButton).toBeTruthy()
    await expandButton!.trigger('click')

    expect(wrapper.text()).toContain('"email"')
    expect(wrapper.text()).toContain('new@acme.test')
  })
})
