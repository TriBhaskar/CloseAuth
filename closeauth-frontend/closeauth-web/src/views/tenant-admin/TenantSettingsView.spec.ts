import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantSettingsView from './TenantSettingsView.vue'
import { clearCsrfToken } from '@/api/csrf'

// FE-5.3–5.7: the settings shell's own job now — tab wiring (URL-synced) and
// the dirty-state guard wired to whichever tab reports it — NOT the tabs'
// individual form logic, which moved to settings/*.spec.ts alongside each
// tab component.
//
// Dialog/DialogContent render through a Teleport — stubbed the same way
// TypedConfirmDialog.spec.ts stubs it, so ConfirmDialog's rendered content
// is queryable in the mounted view's own subtree.
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<div><slot /></div>' },
  DialogDescription: { template: '<div><slot /></div>' },
}

function brandingFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    logoUrl: '',
    primaryColor: '#4F46E5',
    backgroundColor: '#FFFFFF',
    accentColor: '#22D3EE',
    companyName: '',
    ...overrides,
  }
}

function registrationConfigFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return { tenantId: 'tenant-1', mode: 'EMAIL_VERIFIED', ...overrides }
}

function stubSettingsFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/t/acme/api/branding')
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(brandingFixture()),
        })
      if (url === '/t/acme/api/registration-config')
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(registrationConfigFixture()),
        })
      if (url.startsWith('/api/entry/resolve')) return Promise.resolve({ ok: false, status: 404 })
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

async function createSettingsRouter(initialPath = '/t/acme/console/settings') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        path: '/t/:slug/console/settings',
        name: 'tenant-admin-settings',
        component: TenantSettingsView,
      },
      { path: '/t/:slug/console/users', component: { template: '<div>users</div>' } },
    ],
  })
  await router.push(initialPath)
  await router.isReady()
  return router
}

async function mountSettingsView(initialPath?: string) {
  const router = await createSettingsRouter(initialPath)
  const wrapper = mount(TenantSettingsView, { global: { plugins: [router], stubs: dialogStubs } })
  await flushPromises()
  return { router, wrapper }
}

async function switchTab(
  wrapper: Awaited<ReturnType<typeof mountSettingsView>>['wrapper'],
  tab: string,
): Promise<void> {
  await wrapper.find(`[data-tab="${tab}"]`).trigger('mousedown')
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearCsrfToken()
})

describe('TenantSettingsView — tab wiring', () => {
  it('defaults to the Branding tab', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView()
    expect(wrapper.find('#branding-form').exists()).toBe(true)
  })

  it('seeds the active tab from the URL query', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView('/t/acme/console/settings?tab=registration')
    expect(wrapper.find('#registration-form').exists()).toBe(true)
    expect(wrapper.find('#branding-form').exists()).toBe(false)
  })

  it('switching tabs when clean updates both the rendered content and the URL', async () => {
    stubSettingsFetch()
    const { wrapper, router } = await mountSettingsView()
    await switchTab(wrapper, 'registration')

    expect(wrapper.find('#registration-form').exists()).toBe(true)
    expect(router.currentRoute.value.query.tab).toBe('registration')
  })

  it('renders the Sessions and Tenant profile tabs', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView()
    await switchTab(wrapper, 'sessions')
    expect(wrapper.text()).toContain("isn't configurable per tenant yet")

    await switchTab(wrapper, 'profile')
    expect(wrapper.find('#tenant-profile-id').exists()).toBe(true)
  })
})

describe('TenantSettingsView — dirty-state guard (FE-5.7)', () => {
  it('switching away from a dirty Branding tab opens the confirm dialog and does NOT switch until confirmed', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView()

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await flushPromises()

    await switchTab(wrapper, 'registration')

    // Still on Branding — the switch is deferred until the dialog resolves.
    expect(wrapper.find('#branding-form').exists()).toBe(true)
    expect(wrapper.text()).toContain('Discard changes?')
  })

  it('confirming discard proceeds to the target tab', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView()

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await flushPromises()
    await switchTab(wrapper, 'registration')

    await wrapper.find('#confirm-dialog-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('#registration-form').exists()).toBe(true)
  })

  it('cancelling keeps the dirty tab active with its edit intact', async () => {
    stubSettingsFetch()
    const { wrapper } = await mountSettingsView()

    await wrapper.find('#branding-company-name').setValue('Acme Renamed')
    await flushPromises()
    await switchTab(wrapper, 'registration')

    // Cancel button in ConfirmDialog is the non-confirm Button (variant outline).
    const buttons = wrapper.findAll('button').filter((b) => b.text() === 'Cancel')
    expect(buttons.length).toBeGreaterThan(0)
    await buttons[0]!.trigger('click')
    await flushPromises()

    // The Dialog primitive is stubbed as an unconditional pass-through (see
    // dialogStubs above), so its content stays in the DOM regardless of the
    // real `open` state — the meaningful proof here is that the tab switch
    // never happened and the unsaved edit survived, not the dialog's own
    // visibility (covered directly by useDirtyGuard.spec.ts).
    expect(wrapper.find('#branding-form').exists()).toBe(true)
    expect((wrapper.find('#branding-company-name').element as HTMLInputElement).value).toBe(
      'Acme Renamed',
    )
  })
})
