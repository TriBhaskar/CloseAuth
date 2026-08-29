<script setup lang="ts">
// FE-5.1/5.2 (spec §6.4.6): the audit log rebuilt onto the FE-1 primitives —
// filter bar -> DataTable -> a right-side Sheet drawer (AuditEventDrawer.vue)
// for the full event, replacing Stage UI-3e's raw ui/table + inline expand-
// row. Filters are now URL-synced (a filtered view is shareable, per spec)
// rather than component-local — DataTable itself already syncs `page` into
// the URL (see its own header comment); this view syncs the other six.
//
// Two documented deviations from §6.4.6, both because the backend enforces
// them, not because this view chooses to skip them:
//   1. No free-text filter — TenantAuditController has no `q` param.
//      DataTable's `hideSearch` (FE-5.1) suppresses the search box rather
//      than shipping one that filters nothing.
//   2. Single-select event type, not multi-select — the controller takes
//      one `event_type` value.
//
// FE-5.2's filter-honesty guard: every filter error is either a field-level
// message (AUDIT_ERROR_FIELDS-mapped) or a table-level ErrorState — this
// view never renders a loaded-but-empty table for a failing filter. Honesty
// over polish, preserved from Stage UI-3e: events drain from an outbox
// asynchronously (~2s), and the empty state distinguishes "no events match
// these filters" from "no events recorded yet."
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import StateBadge, { auditOutcomeTone } from '@/components/common/StateBadge.vue'
import FormField from '@/components/common/FormField.vue'
import AuditEventDrawer from '@/components/admin/AuditEventDrawer.vue'
import { describeAdminError } from '@/api/problem'
import {
  listAuditEvents,
  describeAuditActor,
  resolveRangePreset,
  AUDIT_EVENT_TYPE_GROUPS,
  AUDIT_ERROR_FIELDS,
  AUDIT_RANGE_PRESETS,
  DEFAULT_AUDIT_PAGE_SIZE,
  type AuditEventView,
  type AuditFilters,
  type AuditRangePreset,
} from '@/api/tenantAdminAudit'
import type { PageView } from '@/api/pageView'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

// ---- local<->ISO helpers for the custom-range datetime-local inputs -------

function toInstantOrUndefined(local: string): string | undefined {
  if (!local) return undefined
  const parsed = new Date(local)
  if (Number.isNaN(parsed.getTime())) return undefined
  return parsed.toISOString()
}

function isoToLocalInput(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function initialRange(): AuditRangePreset {
  const q = route.query.range
  return typeof q === 'string' && (AUDIT_RANGE_PRESETS as string[]).includes(q)
    ? (q as AuditRangePreset)
    : 'custom'
}

// ---- filter bar (staged, applied only on submit; seeded from the URL so a
// reload or a shared link restores the exact same view) -------------------

const filterForm = reactive({
  eventType: typeof route.query.eventType === 'string' ? route.query.eventType : '',
  range: initialRange(),
  fromLocal: typeof route.query.from === 'string' ? isoToLocalInput(route.query.from) : '',
  toLocal: typeof route.query.to === 'string' ? isoToLocalInput(route.query.to) : '',
  userId: typeof route.query.userId === 'string' ? route.query.userId : '',
  clientId: typeof route.query.clientId === 'string' ? route.query.clientId : '',
  actor: typeof route.query.actor === 'string' ? route.query.actor : '',
})
const filterErrors = reactive<Record<string, string>>({})

function computeFilters(): AuditFilters {
  const isCustom = filterForm.range === 'custom'
  const { from: presetFrom } = resolveRangePreset(filterForm.range)
  return {
    eventType: filterForm.eventType || undefined,
    from: isCustom ? toInstantOrUndefined(filterForm.fromLocal) : presetFrom,
    to: isCustom ? toInstantOrUndefined(filterForm.toLocal) : undefined,
    userId: filterForm.userId || undefined,
    clientId: filterForm.clientId || undefined,
    actor: filterForm.actor || undefined,
  }
}

const applied = ref<AuditFilters>(computeFilters())
const hasActiveFilters = computed(() =>
  Object.values(applied.value).some((v) => v !== undefined && v !== ''),
)

function syncQuery(): void {
  const isCustom = filterForm.range === 'custom'
  void router.replace({
    query: {
      ...route.query,
      eventType: applied.value.eventType,
      range: isCustom ? undefined : filterForm.range,
      from: isCustom ? applied.value.from : undefined,
      to: isCustom ? applied.value.to : undefined,
      userId: applied.value.userId,
      clientId: applied.value.clientId,
      actor: applied.value.actor,
      page: undefined,
    },
  })
}

function applyFilters(): void {
  applied.value = computeFilters()
  page.value = 0
  syncQuery()
  void load()
}

function clearFilters(): void {
  filterForm.eventType = ''
  filterForm.range = 'custom'
  filterForm.fromLocal = ''
  filterForm.toLocal = ''
  filterForm.userId = ''
  filterForm.clientId = ''
  filterForm.actor = ''
  applied.value = {}
  page.value = 0
  syncQuery()
  void load()
}

// ---- list ------------------------------------------------------------------

const page = ref(Number(route.query.page) || 0)
const pageData = ref<PageView<AuditEventView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  for (const key of Object.keys(filterErrors)) delete filterErrors[key]

  const result = await listAuditEvents(slug, applied.value, page.value, DEFAULT_AUDIT_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    case 'validationErrors': {
      // FE-5.2: a failing filter is a field error AND a table-level error —
      // never a loaded-but-empty table. `pageData` is cleared so DataTable's
      // `state` computed below falls to 'error', not 'loaded'.
      pageData.value = null
      Object.assign(filterErrors, result.errors)
      isLoading.value = false
      errorMessage.value = 'One or more filters are invalid.'
      break
    }
    case 'error': {
      const field = AUDIT_ERROR_FIELDS[result.code]
      pageData.value = null
      if (field) {
        filterErrors[field] = result.message
        errorMessage.value = 'One or more filters are invalid.'
      } else {
        errorMessage.value = describeAdminError(result)
      }
      isLoading.value = false
      break
    }
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

// ---- drawer ------------------------------------------------------------------

const isDrawerOpen = ref(false)
const selectedEvent = ref<AuditEventView | null>(null)

function openDrawer(event: AuditEventView): void {
  selectedEvent.value = event
  isDrawerOpen.value = true
}

// ---- DataTable columns -------------------------------------------------------

const columns = computed<ColumnDef<AuditEventView, unknown>[]>(() => [
  {
    id: 'when',
    header: 'When',
    cell: ({ row }) => h(RelativeTime, { value: row.original.createdAt }),
  },
  {
    id: 'event',
    header: 'Event',
    cell: ({ row }) => h('span', { class: 'font-mono text-xs' }, row.original.eventType),
  },
  {
    id: 'actor',
    header: 'Actor',
    cell: ({ row }) => h('span', { class: 'text-xs' }, describeAuditActor(row.original)),
  },
  {
    id: 'subject',
    header: 'Subject',
    cell: ({ row }) =>
      row.original.subjectUserId
        ? h(IdentifierChip, { kind: 'user', value: row.original.subjectUserId })
        : h('span', { class: 'text-xs text-muted-foreground' }, '—'),
  },
  {
    id: 'outcome',
    header: 'Outcome',
    cell: ({ row }) =>
      h(StateBadge, { tone: auditOutcomeTone(row.original.outcome), label: row.original.outcome }),
  },
  {
    id: 'ip',
    header: 'IP',
    cell: ({ row }) => h('span', { class: 'font-mono text-xs' }, row.original.ipAddress ?? '—'),
  },
])
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Audit log</h1>
      <p class="text-sm text-muted-foreground">
        Security-relevant events for this tenant, newest first. Read-only.
      </p>
    </div>

    <form
      id="audit-filter-form"
      class="rounded-lg border border-border p-4 flex flex-col gap-4"
      novalidate
      @submit.prevent="applyFilters"
    >
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <FormField id="audit-filter-event-type" label="Event type" :error="filterErrors.eventType">
          <template #default="{ hasError, describedBy }">
            <select
              id="audit-filter-event-type"
              v-model="filterForm.eventType"
              class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            >
              <option value="">Any</option>
              <optgroup
                v-for="group in AUDIT_EVENT_TYPE_GROUPS"
                :key="group.label"
                :label="group.label"
              >
                <option v-for="type in group.types" :key="type" :value="type">
                  {{ type }}{{ group.notYetEmitted ? ' (never emitted yet)' : '' }}
                </option>
              </optgroup>
            </select>
          </template>
        </FormField>

        <div class="flex flex-col gap-1.5">
          <Label for="audit-filter-range">Date range</Label>
          <select
            id="audit-filter-range"
            v-model="filterForm.range"
            class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
          >
            <option value="1h">Last hour</option>
            <option value="24h">Last 24 hours</option>
            <option value="7d">Last 7 days</option>
            <option value="30d">Last 30 days</option>
            <option value="custom">Custom / any time</option>
          </select>
        </div>

        <template v-if="filterForm.range === 'custom'">
          <FormField
            id="audit-filter-from"
            label="From"
            :error="filterErrors.from"
            hint="Inclusive. Leave blank for no lower bound."
          >
            <template #default="{ hasError, describedBy }">
              <Input
                id="audit-filter-from"
                v-model="filterForm.fromLocal"
                type="datetime-local"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <FormField
            id="audit-filter-to"
            label="To"
            :error="filterErrors.to"
            hint="Exclusive — events at exactly this instant are not included."
          >
            <template #default="{ hasError, describedBy }">
              <Input
                id="audit-filter-to"
                v-model="filterForm.toLocal"
                type="datetime-local"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>
        </template>

        <FormField
          id="audit-filter-user-id"
          label="Subject user ID"
          :error="filterErrors.userId"
          hint="The user the event happened to."
        >
          <template #default="{ hasError, describedBy }">
            <Input
              id="audit-filter-user-id"
              v-model="filterForm.userId"
              type="text"
              placeholder="user UUID"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <FormField
          id="audit-filter-actor"
          label="Actor user ID"
          :error="filterErrors.actor"
          hint="The user who performed the action."
        >
          <template #default="{ hasError, describedBy }">
            <Input
              id="audit-filter-actor"
              v-model="filterForm.actor"
              type="text"
              placeholder="user UUID"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <FormField
          id="audit-filter-client-id"
          label="Actor client ID"
          :error="filterErrors.clientId"
          hint="The M2M client that performed the action."
        >
          <template #default="{ hasError, describedBy }">
            <Input
              id="audit-filter-client-id"
              v-model="filterForm.clientId"
              type="text"
              placeholder="OAuth2 client id"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>
      </div>

      <div class="flex items-center gap-2">
        <Button id="audit-apply-filters" type="submit" size="sm">Apply filters</Button>
        <Button
          id="audit-clear-filters"
          type="button"
          variant="outline"
          size="sm"
          @click="clearFilters"
          >Clear filters</Button
        >
      </div>
    </form>

    <div class="flex items-center justify-between gap-4">
      <p class="text-xs text-muted-foreground">
        Events are recorded asynchronously (usually within a couple of seconds). An action performed
        moments ago may not appear below yet — use Refresh to check again, rather than expecting
        this list to update on its own.
      </p>
      <Button id="audit-refresh" variant="outline" size="sm" @click="load">Refresh</Button>
    </div>

    <DataTable
      :columns="columns"
      :data="pageData?.items ?? []"
      :row-key="(e: AuditEventView) => e.id"
      :state="dataTableState"
      :row-attrs="(e: AuditEventView) => ({ 'data-audit-event-id': e.id })"
      :on-row-click="openDrawer"
      :page="pageData?.page ?? page"
      :size="pageData?.size ?? DEFAULT_AUDIT_PAGE_SIZE"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      :has-active-filters="hasActiveFilters"
      hide-search
      empty-title="No audit events recorded yet."
      empty-description="Once something happens in this tenant, it shows up here."
      filtered-empty-title="No audit events match these filters."
      filtered-empty-description="Try widening or clearing your filters."
      @update:page="(p: number) => (page = p)"
      @retry="load"
    />

    <AuditEventDrawer
      :open="isDrawerOpen"
      :event="selectedEvent"
      @update:open="(v: boolean) => (isDrawerOpen = v)"
    />
  </div>
</template>
