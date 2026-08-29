import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StateBadge, { tenantStatusTone, userStatusTone, auditOutcomeTone } from './StateBadge.vue'

describe('StateBadge', () => {
  it('renders the label uppercase-styled (via class, not text transform of the string itself)', () => {
    const wrapper = mount(StateBadge, { props: { tone: 'ok', label: 'ACTIVE' } })
    expect(wrapper.text()).toBe('ACTIVE')
    expect(wrapper.classes()).toContain('uppercase')
  })

  it('the muted tone has no fill — background is transparent, border is the only visual boundary', () => {
    const wrapper = mount(StateBadge, { props: { tone: 'muted', label: 'DELETED' } })
    expect(wrapper.classes()).toContain('bg-transparent')
    expect(wrapper.classes()).toContain('border-line-strong')
  })

  it.each([
    ['warn', 'bg-warn-wash'],
    ['ok', 'bg-ok-wash'],
    ['danger', 'bg-danger-wash'],
    ['accent', 'bg-accent-wash'],
  ] as const)('the %s tone fills with its wash background', (tone, expectedClass) => {
    const wrapper = mount(StateBadge, { props: { tone, label: 'X' } })
    expect(wrapper.classes()).toContain(expectedClass)
  })
})

describe('tenantStatusTone', () => {
  it('maps every TenantStatus value to its spec-table tone', () => {
    expect(tenantStatusTone('PROVISIONING')).toBe('warn')
    expect(tenantStatusTone('ACTIVE')).toBe('ok')
    expect(tenantStatusTone('SUSPENDED')).toBe('danger')
    expect(tenantStatusTone('DELETED')).toBe('muted')
  })
})

describe('userStatusTone', () => {
  it('maps every UserStatus value to a tone', () => {
    expect(userStatusTone('PENDING')).toBe('warn')
    expect(userStatusTone('ACTIVE')).toBe('ok')
    expect(userStatusTone('SUSPENDED')).toBe('danger')
    expect(userStatusTone('DELETED')).toBe('muted')
  })
})

describe('auditOutcomeTone', () => {
  it('maps every AuditOutcome value to a tone', () => {
    expect(auditOutcomeTone('SUCCESS')).toBe('ok')
    expect(auditOutcomeTone('FAILURE')).toBe('warn')
    expect(auditOutcomeTone('ERROR')).toBe('danger')
  })
})
