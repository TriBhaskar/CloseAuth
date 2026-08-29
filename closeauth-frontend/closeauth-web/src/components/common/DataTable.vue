<script setup lang="ts" generic="T">
// FE-1.6 (spec §5): the shared list-view table. Built on @tanstack/vue-table
// (already a dependency, already used by ui/table/utils.ts's valueUpdater)
// for column defs/sorting, rendered through the existing ui/table/*
// primitives rather than hand-rolled markup.
//
// Scope, stated once here rather than at every call site: this component
// owns its OWN page/search state (with URL sync for exactly those two —
// `page`/`q` — since they're the only params generic enough for a reusable
// component to name; arbitrary named filters, e.g. `status`/`role`, are
// screen-specific and stay the caller's job). Sorting is client-side over
// whatever `data` currently holds (typically one server page's worth) via
// TanStack's own sort model; a caller that needs true server-side
// multi-page sort listens for `sort-change` and refetches instead of
// trusting the client sort.
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMediaQuery, useDebounceFn } from '@vueuse/core'
import {
  useVueTable,
  getCoreRowModel,
  getSortedRowModel,
  FlexRender,
  type ColumnDef,
  type SortingState,
} from '@tanstack/vue-table'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  TableEmpty,
} from '@/components/ui/table'
import { Input } from '@/components/ui/input'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'

export type { ColumnDef } from '@tanstack/vue-table'

const props = withDefaults(
  defineProps<{
    columns: ColumnDef<T, unknown>[]
    data: T[]
    rowKey: (row: T) => string | number
    /** Extra DOM attributes (e.g. `{ 'data-tenant-id': row.id }`) applied to each rendered row — desktop <tr> and mobile card alike. Optional; mainly a test/CSS scoping hook. */
    rowAttrs?: (row: T) => Record<string, unknown>
    state: 'loading' | 'error' | 'loaded'
    /** Server-side paging — mirrors AdminPagination's own contract, not reimplemented. */
    page: number
    size: number
    totalElements: number
    totalPages: number
    errorMessage?: string
    errorTraceId?: string
    /** FE-6.1 (spec §7.3): false for a non-retryable category (403/404/429) — the caller passes `errorStateProps(result).retryable` once it has a categorized AdminResult. Defaults true, so every pre-existing caller is unaffected. */
    errorRetryable?: boolean
    emptyTitle?: string
    emptyDescription?: string
    filteredEmptyTitle?: string
    filteredEmptyDescription?: string
    /** True when a search/filter is active — decides empty vs filtered-empty copy. */
    hasActiveFilters?: boolean
    searchPlaceholder?: string
    /** Row click navigates — spec §5's "row click → detail". Omit for a non-navigable table. */
    onRowClick?: (row: T) => void
    /**
     * FE-5.1: suppresses the search input AND its `q` URL sync entirely.
     * Exists for callers with no backend free-text filter to send it to
     * (the tenant audit log — TenantAuditController has no `q` param) — a
     * search box that filters nothing would misrepresent what the table can
     * actually do, the exact dishonesty FE-5.2's filter-honesty guard exists
     * to prevent. Default false: every other caller keeps today's behaviour.
     */
    hideSearch?: boolean
  }>(),
  {
    errorMessage: "Couldn't load this. Try again.",
    emptyTitle: 'Nothing here yet',
    emptyDescription: 'Once records exist, they show up here.',
    filteredEmptyTitle: 'No results match these filters',
    filteredEmptyDescription: 'Try widening or clearing your search.',
    searchPlaceholder: 'Search…',
    hasActiveFilters: false,
    hideSearch: false,
    errorRetryable: true,
  },
)

const emit = defineEmits<{
  (e: 'update:page', page: number): void
  (e: 'update:search', value: string): void
  (e: 'retry'): void
  (e: 'sort-change', sorting: SortingState): void
}>()

// §5: debounced (300ms) search, synced to the URL's `q` param so a filtered
// view is shareable — the one filter param generic enough for this
// component to own directly.
const route = useRoute()
const router = useRouter()

const searchInput = ref(!props.hideSearch && typeof route.query.q === 'string' ? route.query.q : '')

const emitAndSyncSearch = useDebounceFn((value: string) => {
  emit('update:search', value)
  const query = { ...route.query, q: value || undefined }
  void router.replace({ query })
}, 300)

watch(searchInput, (value) => {
  if (props.hideSearch) return
  void emitAndSyncSearch(value)
})

watch(
  () => props.page,
  (page) => {
    const query = { ...route.query, page: page > 0 ? String(page) : undefined }
    void router.replace({ query })
  },
)

const sorting = ref<SortingState>([])

const table = useVueTable({
  get data() {
    return props.data
  },
  get columns() {
    return props.columns
  },
  state: {
    get sorting() {
      return sorting.value
    },
  },
  onSortingChange: (updater) => {
    sorting.value = typeof updater === 'function' ? updater(sorting.value) : updater
    emit('sort-change', sorting.value)
  },
  getCoreRowModel: getCoreRowModel(),
  getSortedRowModel: getSortedRowModel(),
})

const isMdUp = useMediaQuery('(min-width: 768px)')

function ariaSort(sorted: false | 'asc' | 'desc'): 'none' | 'ascending' | 'descending' {
  if (sorted === 'asc') return 'ascending'
  if (sorted === 'desc') return 'descending'
  return 'none'
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <div v-if="!hideSearch" class="flex items-center gap-3">
      <Input
        v-model="searchInput"
        type="search"
        :placeholder="searchPlaceholder"
        class="max-w-xs"
      />
    </div>

    <div v-if="!isMdUp" class="flex flex-col gap-2">
      <template v-if="state === 'loading'">
        <div v-for="i in 3" :key="i" class="skeleton h-16 w-full rounded-md" />
      </template>
      <ErrorState
        v-else-if="state === 'error'"
        :message="errorMessage"
        :trace-id="errorTraceId"
        :retryable="errorRetryable"
        @retry="$emit('retry')"
      />
      <EmptyState
        v-else-if="data.length === 0"
        :title="hasActiveFilters ? filteredEmptyTitle : emptyTitle"
        :description="hasActiveFilters ? filteredEmptyDescription : emptyDescription"
      >
        <template v-if="$slots.action" #action><slot name="action" /></template>
      </EmptyState>
      <template v-else>
        <div
          v-for="row in table.getRowModel().rows"
          :key="rowKey(row.original)"
          v-bind="rowAttrs?.(row.original)"
          class="rounded-md border border-line p-3"
          :class="onRowClick ? 'cursor-pointer hover:bg-surface-sunken' : ''"
          @click="onRowClick?.(row.original)"
        >
          <slot name="card" :row="row.original">
            <dl class="flex flex-col gap-1 text-sm">
              <div
                v-for="cell in row.getVisibleCells()"
                :key="cell.id"
                class="flex justify-between gap-2"
              >
                <dt class="text-ink-muted">
                  {{ String(cell.column.columnDef.header ?? cell.column.id) }}
                </dt>
                <dd class="text-ink text-right">
                  <FlexRender :render="cell.column.columnDef.cell" :props="cell.getContext()" />
                </dd>
              </div>
            </dl>
          </slot>
        </div>
      </template>
    </div>

    <Table v-else>
      <TableHeader class="sticky top-0 bg-surface z-10">
        <TableRow v-for="headerGroup in table.getHeaderGroups()" :key="headerGroup.id">
          <TableHead
            v-for="header in headerGroup.headers"
            :key="header.id"
            :aria-sort="ariaSort(header.column.getIsSorted())"
          >
            <button
              v-if="header.column.getCanSort()"
              type="button"
              class="flex items-center gap-1 hover:text-ink"
              @click="header.column.toggleSorting()"
            >
              <FlexRender :render="header.column.columnDef.header" :props="header.getContext()" />
              <span aria-hidden="true">{{
                header.column.getIsSorted() === 'asc'
                  ? '↑'
                  : header.column.getIsSorted() === 'desc'
                    ? '↓'
                    : ''
              }}</span>
            </button>
            <FlexRender
              v-else
              :render="header.column.columnDef.header"
              :props="header.getContext()"
            />
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <template v-if="state === 'loading'">
          <TableRow v-for="i in 5" :key="i">
            <TableCell :colspan="columns.length">
              <div class="skeleton h-6 w-full rounded" />
            </TableCell>
          </TableRow>
        </template>
        <TableEmpty v-else-if="state === 'error'" :colspan="columns.length">
          <ErrorState
            :message="errorMessage"
            :trace-id="errorTraceId"
            :retryable="errorRetryable"
            @retry="$emit('retry')"
          />
        </TableEmpty>
        <TableEmpty v-else-if="data.length === 0" :colspan="columns.length">
          <EmptyState
            :title="hasActiveFilters ? filteredEmptyTitle : emptyTitle"
            :description="hasActiveFilters ? filteredEmptyDescription : emptyDescription"
          >
            <template v-if="$slots.action" #action><slot name="action" /></template>
          </EmptyState>
        </TableEmpty>
        <TableRow
          v-else
          v-for="row in table.getRowModel().rows"
          :key="rowKey(row.original)"
          v-bind="rowAttrs?.(row.original)"
          :class="onRowClick ? 'cursor-pointer' : ''"
          @click="onRowClick?.(row.original)"
        >
          <TableCell v-for="cell in row.getVisibleCells()" :key="cell.id">
            <FlexRender :render="cell.column.columnDef.cell" :props="cell.getContext()" />
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>

    <!-- FE-6.1: OR'd with totalPages > 1, not just data.length > 0 — a
         client-side filter (TenantClientsView.vue and its siblings) can
         empty the CURRENT server page's filtered view while a real second
         server page still exists. Losing the pager here was a dead end:
         the only way back to the rest of the data was clearing the search.
         data.length > 0 alone still covers every server-side-filtered
         table exactly as before. -->
    <AdminPagination
      v-if="state === 'loaded' && (data.length > 0 || totalPages > 1)"
      :page="page"
      :size="size"
      :total-elements="totalElements"
      :total-pages="totalPages"
      @update:page="(p) => $emit('update:page', p)"
    />
  </div>
</template>
