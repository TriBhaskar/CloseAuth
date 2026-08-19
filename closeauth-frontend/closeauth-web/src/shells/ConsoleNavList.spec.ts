import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { KeyRound, ScrollText, ShieldEllipsis, Users } from 'lucide-vue-next'
import ConsoleNavList, { type ConsoleNavItem } from './ConsoleNavList.vue'

// FE-4a (spec §4.2): grouped sidebar nav — genuinely new logic (the flat
// v-for this replaces had no group concept at all), so it gets a dedicated
// test the way ConsoleShell's own net-new responsive logic did in FE-1c.

async function mountList(navItems: ConsoleNavItem[], collapsed = false) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()
  return mount(ConsoleNavList, {
    props: { navItems, collapsed },
    global: { plugins: [router] },
  })
}

describe('ConsoleNavList', () => {
  it('renders no group labels for a flat array with no group set (PlatformAdminLayout.vue\'s own shape)', async () => {
    const items: ConsoleNavItem[] = [
      { label: 'Tenants', icon: Users, path: '/platform/console/tenants' },
      { label: 'Admins', icon: ShieldEllipsis, path: '/platform/console/admins' },
    ]
    const wrapper = await mountList(items)
    expect(wrapper.text()).toContain('Tenants')
    expect(wrapper.text()).toContain('Admins')
    // No uppercase group-label <p> element should exist at all.
    expect(wrapper.find('p').exists()).toBe(false)
  })

  it('renders one uppercase group label per group, in first-appearance order', async () => {
    const items: ConsoleNavItem[] = [
      { label: 'Users', icon: Users, path: '/t/acme/console/users', group: 'Directory' },
      { label: 'Clients', icon: KeyRound, path: '/t/acme/console/clients', group: 'Applications' },
      { label: 'Roles', icon: ShieldEllipsis, path: '/t/acme/console/roles', group: 'Applications' },
      { label: 'Audit log', icon: ScrollText, path: '/t/acme/console/audit', group: 'Operations' },
    ]
    const wrapper = await mountList(items)
    const labels = wrapper.findAll('p').map((p) => p.text())
    expect(labels).toEqual(['Directory', 'Applications', 'Operations'])
    // Applications' two items both render under the one shared header, not duplicated per item.
    expect(wrapper.text()).toContain('Clients')
    expect(wrapper.text()).toContain('Roles')
  })

  it('never renders group labels in collapsed (icon-rail) mode', async () => {
    const items: ConsoleNavItem[] = [
      { label: 'Users', icon: Users, path: '/t/acme/console/users', group: 'Directory' },
    ]
    const wrapper = await mountList(items, true)
    expect(wrapper.find('p').exists()).toBe(false)
  })
})
