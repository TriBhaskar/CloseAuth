import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { ShieldCheck, Users } from 'lucide-vue-next'
import ConsoleShell from './ConsoleShell.vue'
import type { ConsoleNavItem } from './ConsoleNavList.vue'

// FE-1.10: ConsoleShell's responsive behaviour is net new (neither
// pre-consolidation layout had any automatic breakpoint logic at all), so
// it's the one piece of this session that most needs a real test rather
// than trusting the implementation by inspection. Stubs window.matchMedia
// per query string — the same mechanism @vueuse/core's useMediaQuery calls
// internally — to force each of the three breakpoint states deterministically.
function stubMatchMedia(matchingQueries: string[]) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: matchingQueries.includes(query),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  })
}

const NAV_ITEMS: ConsoleNavItem[] = [{ label: 'Users', icon: Users, path: '/t/acme/console/users' }]

async function mountShell() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' }, meta: { title: 'Overview' } }],
  })
  await router.push('/')
  await router.isReady()

  return mount(ConsoleShell, {
    props: { navItems: NAV_ITEMS, markIcon: ShieldCheck, identityLabel: 'Console', identityTenantId: 'ten_acme' },
    global: { plugins: [router] },
    slots: { default: '<div id="page-content" />' },
  })
}

describe('ConsoleShell', () => {
  beforeEach(() => {
    stubMatchMedia(['(min-width: 1024px)', '(min-width: 768px)'])
  })

  it('renders a static sidebar and the page title at desktop width', async () => {
    const wrapper = await mountShell()
    expect(wrapper.find('aside').exists()).toBe(true)
    expect(wrapper.text()).toContain('Overview')
    expect(wrapper.text()).toContain('Users')
    expect(wrapper.find('#page-content').exists()).toBe(true)
  })

  it('forces icon-rail width between 768px and 1024px regardless of the manual toggle', async () => {
    stubMatchMedia(['(min-width: 768px)']) // isLgUp=false, isMdUp=true
    const wrapper = await mountShell()
    const aside = wrapper.find('aside')
    expect(aside.exists()).toBe(true)
    expect(aside.classes()).toContain('w-[60px]')
    // No collapse button — nothing to toggle while forced.
    expect(wrapper.find('button[type="button"]').exists()).toBe(false)
  })

  it('renders a hamburger + Sheet instead of a static sidebar below 768px', async () => {
    stubMatchMedia([]) // isLgUp=false, isMdUp=false
    const wrapper = await mountShell()
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Open navigation"]').exists()).toBe(true)
  })

  it('shows a tenant IdentifierChip in the sidebar when identityTenantId is given (§4.2: tenant name + Tenant ID chip)', async () => {
    const wrapper = await mountShell()
    // IdentifierChip renders the value as a title attribute on its value span.
    expect(wrapper.find('[title="ten_acme"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('CloseAuth')
  })

  it('shows the plain PLATFORM label instead of a chip when identityTenantId is absent', async () => {
    setActivePinia(createPinia())
    const router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/', name: 'home', component: { template: '<div />' }, meta: { title: 'Overview' } }],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(ConsoleShell, {
      props: { navItems: NAV_ITEMS, markIcon: ShieldCheck, identityLabel: 'PLATFORM' },
      global: { plugins: [router] },
    })
    expect(wrapper.text()).toContain('PLATFORM')
    expect(wrapper.find('[title]').exists()).toBe(false)
  })

  it('the manual collapse toggle works at full desktop width', async () => {
    const wrapper = await mountShell()
    const aside = wrapper.find('aside')
    expect(aside.classes()).toContain('w-[240px]')

    const toggle = wrapper.find('button.h-8.w-full')
    await toggle.trigger('click')
    expect(wrapper.find('aside').classes()).toContain('w-[60px]')
  })
})
