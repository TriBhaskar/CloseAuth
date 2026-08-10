import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TenantClientCredentialsView from './TenantClientCredentialsView.vue'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'
import type { ClientCredentials } from '@/api/tenantAdminClients'

// Stage UI-3c: the centerpiece's required proofs. The write-once secret is
// masked by default, reveal/copy work against the exact value, Continue is
// gated behind the acknowledgement checkbox, an empty store renders the
// unavailable state with no fabricated value (never a blank field standing
// in for real data), and the store is genuinely cleared on acknowledgement
// so a revisit correctly shows unavailable — proving the store is a
// one-hop, in-memory handoff, not a durable cache.

async function createCredentialsRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/clients', name: 'tenant-admin-clients', component: { template: '<div />' } },
      {
        path: '/t/:slug/console/clients/credentials',
        name: 'tenant-admin-client-credentials',
        component: TenantClientCredentialsView,
      },
      {
        path: '/t/:slug/console/clients/:clientId',
        name: 'tenant-admin-client-detail',
        component: { template: '<div />' },
      },
      {
        path: '/t/:slug/console/resource-servers',
        name: 'tenant-admin-resource-servers',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/t/acme/console/clients/credentials')
  await router.isReady()
  return router
}

function credentialsFixture(overrides: Partial<ClientCredentials> = {}): ClientCredentials {
  return {
    client: {
      id: 'record-1',
      clientId: 'billing-api',
      clientName: 'Billing API',
      tenantId: 'tenant-1',
      publicClient: false,
      grantTypes: ['client_credentials'],
      scopes: [],
      redirectUris: [],
    },
    clientSecret: 'sVeryHighEntropySecretValue1234567890abcdef',
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantClientCredentialsView', () => {
  it('empty store: renders the unavailable state, no fabricated secret field', async () => {
    const router = await createCredentialsRouter()
    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#client-credentials-unavailable').exists()).toBe(true)
    expect(wrapper.text()).toContain('no longer available')
    expect(wrapper.find('#client-credentials-secret').exists()).toBe(false)
    expect(wrapper.find('#client-credentials-continue').exists()).toBe(false)
  })

  it('secret is masked by default; reveal toggles the real value', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture(), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    const secretEl = wrapper.find('#client-credentials-secret')
    expect(secretEl.text()).not.toContain('sVeryHighEntropySecretValue1234567890abcdef')
    expect(secretEl.text()).toMatch(/^•+$/)

    await wrapper.find('#client-credentials-reveal').trigger('click')
    expect(wrapper.find('#client-credentials-secret').text()).toBe('sVeryHighEntropySecretValue1234567890abcdef')

    await wrapper.find('#client-credentials-reveal').trigger('click')
    expect(wrapper.find('#client-credentials-secret').text()).toMatch(/^•+$/)
  })

  it('copy writes the exact secret value to the clipboard', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture(), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-credentials-copy-secret').trigger('click')
    await flushPromises()

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('sVeryHighEntropySecretValue1234567890abcdef')
  })

  it('copy client_id and record id write their own exact values, independently', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture(), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-credentials-copy-client-id').trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('billing-api')

    await wrapper.find('#client-credentials-copy-record-id').trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('record-1')
  })

  it('Continue is disabled until the acknowledgement checkbox is checked', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture(), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#client-credentials-continue').attributes('disabled')).toBeDefined()

    await wrapper.find('#client-credentials-ack').trigger('click')
    await flushPromises()

    expect(wrapper.find('#client-credentials-continue').attributes('disabled')).toBeUndefined()
  })

  it('acknowledging and continuing clears the store, so a revisit shows unavailable', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture(), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#client-credentials-ack').trigger('click')
    await wrapper.find('#client-credentials-continue').trigger('click')
    await flushPromises()

    expect(store.credentials).toBeNull()
    expect(router.currentRoute.value.name).toBe('tenant-admin-client-detail')
    expect(router.currentRoute.value.params.clientId).toBe('record-1')

    // A fresh mount against the now-cleared store shows unavailable, not stale data.
    const revisit = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()
    expect(revisit.find('#client-credentials-unavailable').exists()).toBe(true)
  })

  it('the auto-created-RS note appears on the create path but not the regenerate path', async () => {
    const routerCreate = await createCredentialsRouter()
    const createStore = useTenantAdminClientCredentialsStore()
    createStore.set(credentialsFixture(), 'create')
    const createWrapper = mount(TenantClientCredentialsView, { global: { plugins: [routerCreate] } })
    await flushPromises()
    expect(createWrapper.text()).toContain('automatically created a resource server')

    setActivePinia(createPinia())
    const routerRegenerate = await createCredentialsRouter()
    const regenerateStore = useTenantAdminClientCredentialsStore()
    regenerateStore.set(credentialsFixture(), 'regenerate')
    const regenerateWrapper = mount(TenantClientCredentialsView, { global: { plugins: [routerRegenerate] } })
    await flushPromises()
    expect(regenerateWrapper.text()).not.toContain('automatically created a resource server')
  })

  it('a public client create (null secret) shows no secret field but still shows the ids', async () => {
    const router = await createCredentialsRouter()
    const store = useTenantAdminClientCredentialsStore()
    store.set(credentialsFixture({ clientSecret: null }), 'create')

    const wrapper = mount(TenantClientCredentialsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('#client-credentials-secret').exists()).toBe(false)
    expect(wrapper.find('#client-credentials-client-id').text()).toBe('billing-api')
  })
})
