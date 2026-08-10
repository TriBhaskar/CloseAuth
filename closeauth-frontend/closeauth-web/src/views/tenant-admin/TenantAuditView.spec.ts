import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAuditView from './TenantAuditView.vue'

// Stage UI-3e: the audit view's required proofs — the honest empty state
// (distinguishing "no events match these filters" from "no events recorded
// yet"), the filter bar building exactly the query the BFF expects with
// blank filters omitted, a code-only 400 (audit.invalid_from) landing on
// the right filter field, the admin-actor rule (actorUserId absent but
// actorPlatformAdminId present must render as the platform admin, never
// "unknown user"), and eventData rendering as readable JSON rather than
// assuming any fixed shape.
async function createAuditRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/console/audit', name: 'tenant-admin-audit', component: TenantAuditView }],
  })
  await router.push('/t/acme/console/audit')
  await router.isReady()
  return router
}

function emptyPage() {
  return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
}

function eventFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'event-1',
    eventType: 'USER_CREATED',
    outcome: 'SUCCESS',
    tenantId: 'tenant-1',
    subjectUserId: 'user-1',
    actorUserId: 'admin-1',
    createdAt: '2026-01-01T00:00:00Z',
    eventData: { email: 'new@acme.test' },
    ...overrides,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAuditView', () => {
  it('empty with no filters applied: "No audit events recorded yet."', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('No audit events recorded yet.')
    expect(wrapper.findAll('[data-audit-event-id]').length).toBe(0)
  })

  it('empty WITH a filter applied: "No audit events match these filters." — a different, honest message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) })
        }
        if (url === '/t/acme/api/audit-events?event_type=USER_DELETED&page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-filter-event-type').setValue('USER_DELETED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('No audit events match these filters.')
    expect(wrapper.text()).not.toContain('No audit events recorded yet.')
  })

  it('applying a single filter builds exactly the expected query, blanks omitted', async () => {
    const fetchMock = vi.fn((url: string) => {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) })
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-filter-event-type').setValue('USER_CREATED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/t/acme/api/audit-events?event_type=USER_CREATED&page=0&size=20', expect.anything())
  })

  it('applying the three id filters builds exactly the expected query, blanks omitted', async () => {
    const fetchMock = vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }))
    vi.stubGlobal('fetch', fetchMock)

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-filter-user-id').setValue('user-1')
    await wrapper.find('#audit-filter-actor').setValue('admin-1')
    await wrapper.find('#audit-filter-client-id').setValue('client-1')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/t/acme/api/audit-events?user_id=user-1&client_id=client-1&actor=admin-1&page=0&size=20',
      expect.anything(),
    )
  })

  it('clear resets the filters and re-requests the unfiltered list', async () => {
    const fetchMock = vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }))
    vi.stubGlobal('fetch', fetchMock)

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-filter-event-type').setValue('USER_CREATED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    await wrapper.find('#audit-clear-filters').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith('/t/acme/api/audit-events?page=0&size=20', expect.anything())
  })

  it('audit.invalid_from (a code-only 400) lands on the From field, not a generic banner', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) })
        }
        if (url.startsWith('/t/acme/api/audit-events?from=')) {
          return Promise.resolve({
            ok: false,
            status: 400,
            json: () =>
              Promise.resolve({ error: 'audit.invalid_from', error_description: "Invalid value for 'from': not-a-date" }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-filter-from').setValue('2026-01-01T00:00')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#audit-filter-from-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe("Invalid value for 'from': not-a-date")
    // Never a generic top-of-page banner alongside the field-level error.
    expect(wrapper.findAll('[role="alert"]').length).toBe(1)
  })

  it('an admin-driven mutation (actorUserId absent, actorPlatformAdminId present) renders "CloseAuth platform admin", never "unknown user"', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [eventFixture({ actorUserId: undefined, actorPlatformAdminId: 'platform-admin-1' })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.find('[data-audit-event-id="event-1"]')
    expect(row.text()).toContain('CloseAuth platform admin')
    expect(row.text().toLowerCase()).not.toContain('unknown')
  })

  it('eventData renders as formatted JSON when a row is expanded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                items: [eventFixture({ eventData: { email: 'new@acme.test', status: 'ACTIVE' } })],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const router = await createAuditRouter()
    const wrapper = mount(TenantAuditView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('#audit-expand-event-1').trigger('click')
    await flushPromises()

    const pre = wrapper.find('[data-audit-event-detail="event-1"] pre')
    expect(pre.exists()).toBe(true)
    expect(pre.text()).toContain('"email": "new@acme.test"')
    expect(pre.text()).toContain('"status": "ACTIVE"')
  })
})
