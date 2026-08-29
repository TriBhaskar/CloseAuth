import { describe, it, expect } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { useDirtyGuard } from './useDirtyGuard'

// FE-5.7: onBeforeRouteLeave only registers against a component instance
// resolved by the router (a route's own component), so these tests mount a
// small host component at a real route rather than calling the composable
// bare — the same shape TenantSettingsView.vue will actually use it in.
const Host = defineComponent({
  setup() {
    const isDirty = ref(false)
    const guard = useDirtyGuard(isDirty)
    return { isDirty, ...guard }
  },
  template: '<div>host</div>',
})

// mount()'s $refs typing doesn't surface a plain object's setup return —
// this narrows it back to the shape Host actually exposes, avoiding `any`.
interface HostExposed {
  isDirty: boolean
  confirmOpen: boolean
  guardTabChange: (next: () => void) => void
  confirmDiscard: () => void
  cancelDiscard: () => void
}

// onBeforeRouteLeave only registers against a component instance the router
// itself resolved as the CURRENT route's matched component — mounting Host
// directly (bypassing <router-view>) leaves it unmatched, and the guard
// silently no-ops (Vue Router logs a dev warning and lets navigation
// through). Mounting a small root with a real <router-view> is what makes
// this test true to how TenantSettingsView.vue is actually reached (nested
// under TenantAdminLayout.vue's own <router-view>, per app/router.ts).
const Root = defineComponent({
  template: '<router-view />',
})

async function mountHost() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/settings', component: Host },
      { path: '/other', component: { template: '<div>other</div>' } },
    ],
  })
  await router.push('/settings')
  await router.isReady()
  const wrapper = mount(Root, { global: { plugins: [router] } })
  const vm = wrapper.findComponent(Host).vm as unknown as HostExposed
  return { router, wrapper, vm }
}

describe('useDirtyGuard — guardTabChange', () => {
  it('calls next() immediately when not dirty', async () => {
    const { vm } = await mountHost()
    let called = false
    vm.guardTabChange(() => (called = true))
    expect(called).toBe(true)
    expect(vm.confirmOpen).toBe(false)
  })

  it('defers next() and opens the dialog when dirty; confirmDiscard runs it and clears isDirty', async () => {
    const { vm } = await mountHost()
    vm.isDirty = true
    let called = false
    vm.guardTabChange(() => (called = true))

    expect(called).toBe(false)
    expect(vm.confirmOpen).toBe(true)

    vm.confirmDiscard()
    expect(called).toBe(true)
    expect(vm.confirmOpen).toBe(false)
    expect(vm.isDirty).toBe(false)
  })

  it('cancelDiscard leaves next() uncalled and isDirty untouched', async () => {
    const { vm } = await mountHost()
    vm.isDirty = true
    let called = false
    vm.guardTabChange(() => (called = true))

    vm.cancelDiscard()
    expect(called).toBe(false)
    expect(vm.confirmOpen).toBe(false)
    expect(vm.isDirty).toBe(true)
  })
})

describe('useDirtyGuard — onBeforeRouteLeave', () => {
  it('allows navigation immediately when not dirty', async () => {
    const { router } = await mountHost()
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/other')
  })

  it('blocks navigation until confirmDiscard resolves it', async () => {
    const { router, vm } = await mountHost()
    vm.isDirty = true

    const navigation = router.push('/other')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings')
    expect(vm.confirmOpen).toBe(true)

    vm.confirmDiscard()
    await navigation
    expect(router.currentRoute.value.path).toBe('/other')
  })

  it('cancelDiscard blocks the navigation — the route stays put', async () => {
    const { router, vm } = await mountHost()
    vm.isDirty = true

    const navigation = router.push('/other')
    await flushPromises()
    vm.cancelDiscard()
    await navigation

    expect(router.currentRoute.value.path).toBe('/settings')
    expect(vm.isDirty).toBe(true)
  })
})
