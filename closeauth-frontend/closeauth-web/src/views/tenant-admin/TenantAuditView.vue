<script setup lang="ts">
// Stage UI-3e: the audit log — the console's first FILTERED list. No prior
// admin view (users/clients/resource-servers/roles) has ever had a filter;
// every one of them takes only page/size, so this is genuinely new
// construction, not composition, for the filter bar specifically. Filter
// state is component-local (a staged `filterForm` object, applied to the
// actual request only on submit via `applied`) — no URL sync, consistent
// with every other list view's page state, which also isn't URL-synced.
//
// Three distinctly-labelled id filters, not three identical "ID" boxes:
// Subject user (`user_id` — who was acted ON), Actor user (`actor` — who did
// it), Actor client (`client_id` — an M2M client that did it). Conflating
// these would be actively misleading, not just imprecise.
//
// Read-only: no mutation anywhere on this page, so no ConfirmDialog and no
// CSRF path — the one admin-CRUD surface in this console that doesn't need
// either.
//
// Honesty over polish in two places: (1) events are written through an
// outbox and drained asynchronously (~2s) — a just-performed action may not
// be listed yet, and this view says so plainly next to a plain Refresh
// button rather than faking a "refreshing…" illusion that implies it's
// actively watching for new rows. (2) the empty state distinguishes "no
// events match these filters" from "no events recorded yet" — collapsing
// those into one message would misrepresent which case the admin is in.
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  listAuditEvents,
  describeAuditActor,
  AUDIT_EVENT_TYPE_GROUPS,
  AUDIT_ERROR_FIELDS,
  DEFAULT_AUDIT_PAGE_SIZE,
  type AuditEventView,
  type AuditFilters,
} from '@/api/tenantAdminAudit'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const slug = String(route.params.slug ?? '')

// ---- filter bar (staged, applied only on submit) -----------------------

const filterForm = reactive({
  eventType: '',
  fromLocal: '', // datetime-local strings — converted to ISO instants on apply
  toLocal: '',
  userId: '',
  clientId: '',
  actor: '',
})
const filterErrors = reactive<Record<string, string>>({})

const applied = ref<AuditFilters>({})
const hasActiveFilters = computed(() => Object.values(applied.value).some((v) => v !== undefined && v !== ''))

function toInstantOrUndefined(local: string): string | undefined {
  if (!local) return undefined
  const parsed = new Date(local)
  if (Number.isNaN(parsed.getTime())) return undefined
  return parsed.toISOString()
}

function applyFilters(): void {
  applied.value = {
    eventType: filterForm.eventType || undefined,
    from: toInstantOrUndefined(filterForm.fromLocal),
    to: toInstantOrUndefined(filterForm.toLocal),
    userId: filterForm.userId || undefined,
    clientId: filterForm.clientId || undefined,
    actor: filterForm.actor || undefined,
  }
  page.value = 0
  void load()
}

function clearFilters(): void {
  filterForm.eventType = ''
  filterForm.fromLocal = ''
  filterForm.toLocal = ''
  filterForm.userId = ''
  filterForm.clientId = ''
  filterForm.actor = ''
  applied.value = {}
  page.value = 0
  void load()
}

// ---- list ----------------------------------------------------------------

const page = ref(0)
const pageData = ref<PageView<AuditEventView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const expandedEventId = ref<string | null>(null)

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
    case 'validationErrors':
      pageData.value = null
      Object.assign(filterErrors, result.errors)
      isLoading.value = false
      break
    case 'error': {
      const field = AUDIT_ERROR_FIELDS[result.code]
      pageData.value = null
      if (field) filterErrors[field] = result.message
      else errorMessage.value = describeAdminError(result)
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
watch(page, load)

function toggleExpanded(eventId: string): void {
  expandedEventId.value = expandedEventId.value === eventId ? null : eventId
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString()
}

function formatEventData(data: unknown): string {
  return JSON.stringify(data, null, 2)
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Audit log</h1>
      <p class="text-sm text-muted-foreground">Security-relevant events for this tenant, newest first. Read-only.</p>
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
              <optgroup v-for="group in AUDIT_EVENT_TYPE_GROUPS" :key="group.label" :label="group.label">
                <option v-for="type in group.types" :key="type" :value="type">
                  {{ type }}{{ group.notYetEmitted ? ' (never emitted yet)' : '' }}
                </option>
              </optgroup>
            </select>
          </template>
        </FormField>

        <FormField id="audit-filter-from" label="From" :error="filterErrors.from" hint="Inclusive.">
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

        <FormField id="audit-filter-to" label="To" :error="filterErrors.to" hint="Exclusive — events at exactly this instant are not included.">
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

        <FormField id="audit-filter-user-id" label="Subject user ID" :error="filterErrors.userId" hint="The user the event happened to.">
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

        <FormField id="audit-filter-actor" label="Actor user ID" :error="filterErrors.actor" hint="The user who performed the action.">
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

        <FormField id="audit-filter-client-id" label="Actor client ID" :error="filterErrors.clientId" hint="The M2M client that performed the action.">
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
        <Button id="audit-clear-filters" type="button" variant="outline" size="sm" @click="clearFilters">Clear</Button>
      </div>
    </form>

    <div class="flex items-center justify-between gap-4">
      <p class="text-xs text-muted-foreground">
        Events are recorded asynchronously (usually within a couple of seconds). An action performed moments ago may
        not appear below yet — use Refresh to check again, rather than expecting this list to update on its own.
      </p>
      <Button id="audit-refresh" variant="outline" size="sm" @click="load">Refresh</Button>
    </div>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>When</TableHead>
                <TableHead>Event</TableHead>
                <TableHead>Outcome</TableHead>
                <TableHead>Subject</TableHead>
                <TableHead>Actor</TableHead>
                <TableHead>Details</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="6">
                {{ hasActiveFilters ? 'No audit events match these filters.' : 'No audit events recorded yet.' }}
              </TableEmpty>
              <template v-for="event in pageData?.items ?? []" :key="event.id">
                <TableRow :data-audit-event-id="event.id">
                  <TableCell class="whitespace-nowrap text-xs">{{ formatDate(event.createdAt) }}</TableCell>
                  <TableCell class="font-mono text-xs">{{ event.eventType }}</TableCell>
                  <TableCell>
                    <Badge :variant="event.outcome === 'SUCCESS' ? 'secondary' : 'destructive'">{{ event.outcome }}</Badge>
                  </TableCell>
                  <TableCell class="font-mono text-xs">{{ event.subjectUserId ?? '—' }}</TableCell>
                  <TableCell class="text-xs">{{ describeAuditActor(event) }}</TableCell>
                  <TableCell>
                    <Button :id="`audit-expand-${event.id}`" variant="outline" size="sm" @click="toggleExpanded(event.id)">
                      {{ expandedEventId === event.id ? 'Hide' : 'View' }}
                    </Button>
                  </TableCell>
                </TableRow>
                <TableRow v-if="expandedEventId === event.id" :data-audit-event-detail="event.id">
                  <TableCell colspan="6">
                    <div class="flex flex-col gap-2 text-xs">
                      <div v-if="event.resourceServerId"><span class="font-medium">Resource server:</span> {{ event.resourceServerId }}</div>
                      <div v-if="event.ipAddress"><span class="font-medium">IP address:</span> {{ event.ipAddress }}</div>
                      <div v-if="event.userAgent"><span class="font-medium">User agent:</span> {{ event.userAgent }}</div>
                      <div v-if="event.errorCode"><span class="font-medium">Error code:</span> {{ event.errorCode }}</div>
                      <div>
                        <Label class="font-medium">Event data</Label>
                        <pre class="mt-1 rounded-md border border-border bg-muted p-3 overflow-x-auto">{{ formatEventData(event.eventData) }}</pre>
                      </div>
                    </div>
                  </TableCell>
                </TableRow>
              </template>
            </TableBody>
          </Table>
        </div>

        <AdminPagination
          v-if="pageData"
          :page="pageData.page"
          :size="pageData.size"
          :total-elements="pageData.totalElements"
          :total-pages="pageData.totalPages"
          @update:page="(p) => (page = p)"
        />
      </div>
    </QueryState>
  </div>
</template>
