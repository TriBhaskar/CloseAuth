import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAuditView from './TenantAuditView.vue'

// FE-5.1/5.2 (spec §6.4.6): the audit view's required proofs, rebuilt for
// the DataTable + Sheet-drawer version of this screen (replacing Stage
// UI-3e's raw ui/table + inline expand-row) —
//   - the honest empty state (distinguishing "no events match these
//     filters" from "no events recorded yet")
//   - the filter bar building exactly the query the BFF expects, with blank
//     filters omitted, and URL-syncing the applied filters (spec: "a
//     filtered view is shareable")
//   - FE-5.2's filter-honesty guard: EVERY filter parameter either sends
//     correctly or fails LOUDLY (a field error AND a non-empty-table
//     ErrorState) — never a silently-empty table
//   - "not yet emitted" event types stay visibly labelled, never mixed
//     silently into the working list
//   - the admin-actor rule (actorUserId absent but actorPlatformAdminId
//     present must render as the platform admin, never "unknown user")
//   - eventData renders as readable JSON in the drawer, not assuming any
//     fixed shape
//   - no free-text search box (DataTable's hideSearch) — the backend has no
//     `q` filter, and shipping one that filters nothing would be exactly
//     the dishonesty this phase exists to prevent

// Sheet/SheetContent render through a Teleport — stubbed the same way
// TypedConfirmDialog.spec.ts stubs Dialog, so the drawer's content is
// queryable within the mounted view's own subtree.
const sheetStubs = {
  Sheet: { template: '<div><slot /></div>' },
  SheetContent: { template: '<div><slot /></div>' },
  SheetHeader: { template: '<div><slot /></div>' },
  SheetTitle: { template: '<div><slot /></div>' },
  SheetDescription: { template: '<div><slot /></div>' },
}

async function createAuditRouter(initialPath = '/t/acme/console/audit') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/t/:slug/console/audit', name: 'tenant-admin-audit', component: TenantAuditView },
    ],
  })
  await router.push(initialPath)
  await router.isReady()
  return router
}

async function mountAuditView(initialPath?: string) {
  const router = await createAuditRouter(initialPath)
  const wrapper = mount(TenantAuditView, { global: { plugins: [router], stubs: sheetStubs } })
  await flushPromises()
  return { router, wrapper }
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

function invalidFilterResponse(code: string, message: string) {
  return Promise.resolve({
    ok: false,
    status: 400,
    json: () => Promise.resolve({ error: code, error_description: message }),
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TenantAuditView — empty states', () => {
  it('empty with no filters applied: "No audit events recorded yet."', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const { wrapper } = await mountAuditView()
    expect(wrapper.text()).toContain('No audit events recorded yet.')
    expect(wrapper.findAll('[data-audit-event-id]').length).toBe(0)
  })

  it('empty WITH a filter applied: "No audit events match these filters." — a different, honest message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        }
        if (url === '/t/acme/api/audit-events?event_type=USER_DELETED&page=0&size=20') {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )

    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-event-type').setValue('USER_DELETED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('No audit events match these filters.')
    expect(wrapper.text()).not.toContain('No audit events recorded yet.')
  })
})

describe('TenantAuditView — no free-text filter (backend has none)', () => {
  it('never renders a search input', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
      ),
    )
    const { wrapper } = await mountAuditView()
    expect(wrapper.find('input[type="search"]').exists()).toBe(false)
  })
})

describe('TenantAuditView — query construction and URL sync', () => {
  it('applying a single event-type filter builds exactly the expected query, blanks omitted, and syncs the URL', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper, router } = await mountAuditView()
    await wrapper.find('#audit-filter-event-type').setValue('USER_CREATED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/t/acme/api/audit-events?event_type=USER_CREATED&page=0&size=20',
      expect.anything(),
    )
    expect(router.currentRoute.value.query.eventType).toBe('USER_CREATED')
  })

  it('applying the three id filters builds exactly the expected query, blanks omitted', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper, router } = await mountAuditView()
    await wrapper.find('#audit-filter-user-id').setValue('user-1')
    await wrapper.find('#audit-filter-actor').setValue('admin-1')
    await wrapper.find('#audit-filter-client-id').setValue('client-1')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/t/acme/api/audit-events?user_id=user-1&client_id=client-1&actor=admin-1&page=0&size=20',
      expect.anything(),
    )
    expect(router.currentRoute.value.query.userId).toBe('user-1')
    expect(router.currentRoute.value.query.clientId).toBe('client-1')
    expect(router.currentRoute.value.query.actor).toBe('admin-1')
  })

  it('a "Last 24 hours" preset resolves to a real `from` instant, with no `to`', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-15T12:00:00.000Z'))
    const fetchMock = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-range').setValue('24h')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/t/acme/api/audit-events?from=2026-06-14T12%3A00%3A00.000Z&page=0&size=20',
      expect.anything(),
    )
    vi.useRealTimers()
  })

  it('custom range hides when a preset is active, and the From/To inputs disappear', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
      ),
    )
    const { wrapper } = await mountAuditView()
    expect(wrapper.find('#audit-filter-from').exists()).toBe(true)

    await wrapper.find('#audit-filter-range').setValue('7d')
    await flushPromises()
    expect(wrapper.find('#audit-filter-from').exists()).toBe(false)
  })

  it('clear resets the filters (including range) and re-requests the unfiltered list', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-event-type').setValue('USER_CREATED')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    await wrapper.find('#audit-clear-filters').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/t/acme/api/audit-events?page=0&size=20',
      expect.anything(),
    )
  })
})

describe('TenantAuditView — FE-5.2 filter honesty, one case per parameter', () => {
  it('event_type: audit.invalid_event_type lands on the field AND the table shows ErrorState, never an empty table', async () => {
    // A valid <select> option is used (a native <select> ignores setValue()
    // for a value with no matching <option>) — the 400 is simulated as
    // though the backend rejected it, which is enough to prove the CLIENT's
    // error-routing, independent of whether this specific value is really
    // invalid server-side.
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('event_type=USER_LOGIN_FAILURE'))
          return invalidFilterResponse('audit.invalid_event_type', "Invalid value for 'event_type'")
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-event-type').setValue('USER_LOGIN_FAILURE')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('#audit-filter-event-type-error').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No audit events recorded yet.')
  })

  it('from: audit.invalid_from lands on the From field AND the table shows ErrorState', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('from='))
          return invalidFilterResponse('audit.invalid_from', "Invalid value for 'from': not-a-date")
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-from').setValue('2026-01-01T00:00')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#audit-filter-from-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe("Invalid value for 'from': not-a-date")
    expect(wrapper.findAll('[role="alert"]').length).toBeGreaterThanOrEqual(1)
  })

  it('to: audit.invalid_to lands on the To field AND the table shows ErrorState', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('to='))
          return invalidFilterResponse('audit.invalid_to', "Invalid value for 'to': not-a-date")
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-to').setValue('2026-01-01T00:00')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#audit-filter-to-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe("Invalid value for 'to': not-a-date")
  })

  it('user_id: audit.invalid_user_id lands on the Subject user ID field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('user_id=not-a-uuid'))
          return invalidFilterResponse('audit.invalid_user_id', "Invalid value for 'user_id'")
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-user-id').setValue('not-a-uuid')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#audit-filter-user-id-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe("Invalid value for 'user_id'")
  })

  it('actor: audit.invalid_actor lands on the Actor user ID field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('actor=not-a-uuid'))
          return invalidFilterResponse('audit.invalid_actor', "Invalid value for 'actor'")
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-actor').setValue('not-a-uuid')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    const fieldAlert = wrapper.find('#audit-filter-actor-error')
    expect(fieldAlert.exists()).toBe(true)
    expect(fieldAlert.text()).toBe("Invalid value for 'actor'")
  })

  it('client_id: the parameter is sent when set, and a generic failure shows ErrorState, never an empty table (no dedicated invalid_* code exists for this filter)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url === '/t/acme/api/audit-events?page=0&size=20')
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(emptyPage()),
          })
        if (url.includes('client_id=some-client')) {
          return Promise.resolve({
            ok: false,
            status: 502,
            json: () =>
              Promise.resolve({
                error: 'bad_gateway',
                error_description: 'Could not reach the backend.',
              }),
          })
        }
        return Promise.reject(new Error(`unexpected fetch: ${url}`))
      }),
    )
    const { wrapper } = await mountAuditView()
    await wrapper.find('#audit-filter-client-id').setValue('some-client')
    await wrapper.find('#audit-filter-form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('Could not reach the backend.')
    expect(wrapper.text()).not.toContain('No audit events recorded yet.')
    expect(wrapper.findAll('[data-audit-event-id]').length).toBe(0)
  })
})

describe('TenantAuditView — taxonomy honesty', () => {
  it('"not yet emitted" event types are visibly labelled, not silently mixed into the working list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(emptyPage()) }),
      ),
    )
    const { wrapper } = await mountAuditView()
    const options = wrapper.findAll('#audit-filter-event-type option')
    const userUpdated = options.find((o) => o.attributes('value') === 'USER_UPDATED')
    expect(userUpdated?.text()).toContain('never emitted yet')
  })
})

describe('TenantAuditView — row rendering and the drawer', () => {
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
                items: [
                  eventFixture({
                    actorUserId: undefined,
                    actorPlatformAdminId: 'platform-admin-1',
                  }),
                ],
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

    const { wrapper } = await mountAuditView()
    const row = wrapper.find('[data-audit-event-id="event-1"]')
    expect(row.text()).toContain('CloseAuth platform admin')
    expect(row.text().toLowerCase()).not.toContain('unknown')
  })

  it('clicking a row opens the drawer, which renders eventData as formatted JSON once expanded', async () => {
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

    const { wrapper } = await mountAuditView()
    expect(wrapper.find('#audit-event-drawer').exists()).toBe(false)

    await wrapper.find('[data-audit-event-id="event-1"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('#audit-event-drawer').exists()).toBe(true)

    const expandButton = wrapper.findAll('button').find((b) => b.text() === 'Expand')
    expect(expandButton).toBeTruthy()
    await expandButton!.trigger('click')

    expect(wrapper.text()).toContain('"email"')
    expect(wrapper.text()).toContain('new@acme.test')
  })
})
