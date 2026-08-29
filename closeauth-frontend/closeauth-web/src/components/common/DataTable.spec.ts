import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import type { ColumnDef } from '@tanstack/vue-table'
import DataTable from './DataTable.vue'

interface Row {
  id: string
  name: string
  status: string
}

const ROWS: Row[] = [
  { id: '1', name: 'Bravo', status: 'ACTIVE' },
  { id: '2', name: 'Alpha', status: 'PENDING' },
]

const COLUMNS: ColumnDef<Row, unknown>[] = [
  { id: 'name', header: 'Name', accessorKey: 'name', enableSorting: true },
  { id: 'status', header: 'Status', accessorKey: 'status', enableSorting: false },
]

function stubMatchMedia(desktop: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: desktop,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  })
}

// Hand-declared rather than derived via InstanceType<typeof DataTable>
// ['$props'] — TypeScript doesn't infer the generic `T` through a generic
// SFC's instance type cleanly, and this is simpler than fighting it.
interface DataTableTestProps {
  columns: ColumnDef<Row, unknown>[]
  data: Row[]
  rowKey: (row: Row) => string | number
  state: 'loading' | 'error' | 'loaded'
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasActiveFilters?: boolean
  errorMessage?: string
  errorRetryable?: boolean
  emptyTitle?: string
  filteredEmptyTitle?: string
  onRowClick?: (row: Row) => void
  hideSearch?: boolean
}

// Vue Test Utils' mount() can't infer a generic SFC's type param from the
// props object it's given (the generic defaults to `unknown`, which then
// rejects every concretely-typed prop below). Casting the component
// reference to a concretely-typed constructor for this test file only is
// the standard workaround — the runtime behaviour under test is unaffected,
// this only narrows what TypeScript believes mount()'s props parameter is.
type DataTableRowComponent = new () => { $props: DataTableTestProps }

async function mountTable(
  props: Partial<DataTableTestProps> = {},
  desktop = true,
  slots: Record<string, string> = {},
) {
  stubMatchMedia(desktop)
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()

  return mount(DataTable as unknown as DataTableRowComponent, {
    props: {
      columns: COLUMNS,
      data: ROWS,
      rowKey: (r: Row) => r.id,
      state: 'loaded',
      page: 0,
      size: 20,
      totalElements: ROWS.length,
      totalPages: 1,
      ...props,
    },
    slots,
    global: { plugins: [router] },
  })
}

beforeEach(() => {
  vi.useFakeTimers()
})

describe('DataTable', () => {
  it('renders rows via the desktop table when loaded', async () => {
    const wrapper = await mountTable()
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('Bravo')
    expect(wrapper.text()).toContain('Alpha')
  })

  it('shows a static skeleton (no shimmer class) while loading, no rows', async () => {
    const wrapper = await mountTable({ state: 'loading', data: [] })
    expect(wrapper.find('.skeleton').exists()).toBe(true)
    expect(wrapper.findAll('tbody tr td .skeleton').length).toBeGreaterThan(0)
    expect(wrapper.text()).not.toContain('Bravo')
  })

  it('shows ErrorState with retry when state is error, and emits retry', async () => {
    const wrapper = await mountTable({
      state: 'error',
      data: [],
      errorMessage: 'Could not reach the backend.',
    })
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Could not reach the backend.')
    await wrapper.find('[role="alert"] button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('FE-6.1: errorRetryable=false suppresses the retry action — a 403 must not offer a retry', async () => {
    const wrapper = await mountTable({
      state: 'error',
      data: [],
      errorMessage: "You don't have access to this.",
      errorRetryable: false,
    })
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.find('[role="alert"] button').exists()).toBe(false)
  })

  it('FE-6.1: the pager stays visible when the current (filtered) view is empty but the server has more pages', async () => {
    const wrapper = await mountTable({
      data: [],
      hasActiveFilters: true,
      totalPages: 2,
      totalElements: 5,
    })
    expect(wrapper.find('#admin-pagination-next').exists()).toBe(true)
  })

  it('the pager is hidden when the view is empty and there is genuinely only one server page', async () => {
    const wrapper = await mountTable({
      data: [],
      hasActiveFilters: false,
      totalPages: 1,
      totalElements: 0,
    })
    expect(wrapper.find('#admin-pagination-next').exists()).toBe(false)
  })

  it('FE-6.2: the mobile empty state forwards the #action slot, same as desktop', async () => {
    const wrapper = await mountTable({ data: [] }, false, {
      action: '<button id="mobile-empty-action">Create one</button>',
    })
    expect(wrapper.find('#mobile-empty-action').exists()).toBe(true)
  })

  it('shows the first-run empty state when data is empty and no filters are active', async () => {
    const wrapper = await mountTable({
      data: [],
      hasActiveFilters: false,
      emptyTitle: 'No tenants yet',
    })
    expect(wrapper.text()).toContain('No tenants yet')
  })

  it('shows the filtered-empty state when data is empty and filters ARE active', async () => {
    const wrapper = await mountTable({
      data: [],
      hasActiveFilters: true,
      filteredEmptyTitle: 'No results match these filters',
    })
    expect(wrapper.text()).toContain('No results match these filters')
  })

  it('clicking a sortable header toggles sort and emits sort-change; an unsortable header has no button', async () => {
    const wrapper = await mountTable()
    const headers = wrapper.findAll('th')
    const nameHeaderButton = headers[0]!.find('button')
    expect(nameHeaderButton.exists()).toBe(true)
    const statusHeaderButton = headers[1]!.find('button')
    expect(statusHeaderButton.exists()).toBe(false)

    await nameHeaderButton.trigger('click')
    expect(wrapper.emitted('sort-change')).toBeTruthy()
    expect(headers[0]!.attributes('aria-sort')).toBe('ascending')
  })

  it('row click invokes onRowClick with the row data', async () => {
    const onRowClick = vi.fn()
    const wrapper = await mountTable({ onRowClick })
    await wrapper.findAll('tbody tr')[0]!.trigger('click')
    expect(onRowClick).toHaveBeenCalledWith(expect.objectContaining({ name: 'Bravo' }))
  })

  it('paging delegates to AdminPagination and forwards update:page', async () => {
    const wrapper = await mountTable({ page: 0, totalPages: 2, totalElements: 21 })
    const nextButton = wrapper.find('#admin-pagination-next')
    expect(nextButton.exists()).toBe(true)
    await nextButton.trigger('click')
    expect(wrapper.emitted('update:page')).toEqual([[1]])
  })

  it('search is debounced 300ms before emitting update:search', async () => {
    const wrapper = await mountTable()
    await wrapper.find('input[type="search"]').setValue('acme')
    expect(wrapper.emitted('update:search')).toBeFalsy()
    vi.advanceTimersByTime(299)
    await flushPromises()
    expect(wrapper.emitted('update:search')).toBeFalsy()
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(wrapper.emitted('update:search')).toEqual([['acme']])
  })

  it('hideSearch suppresses the search input entirely — FE-5.1, callers with no free-text backend filter', async () => {
    const wrapper = await mountTable({ hideSearch: true })
    expect(wrapper.find('input[type="search"]').exists()).toBe(false)
  })

  it('renders the mobile stacked-card layout instead of a <table> below 768px', async () => {
    const wrapper = await mountTable({}, false)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).toContain('Bravo')
    expect(wrapper.text()).toContain('Alpha')
  })

  it('mobile card layout falls back to a naive key-value dump when no #card slot is given', async () => {
    const wrapper = await mountTable({}, false)
    expect(wrapper.text()).toContain('Name')
    expect(wrapper.text()).toContain('Bravo')
  })
})
