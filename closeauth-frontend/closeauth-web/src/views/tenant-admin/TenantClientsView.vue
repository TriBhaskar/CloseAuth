<script setup lang="ts">
// FE-4.10: rebuilt onto DataTable now that TenantClientController exposes a
// real tenant-scoped list (closing the gap Stage UI-3c/FE-4c built honestly
// around via EmptyState + a record-id lookup form). Same starting point
// TenantResourceServersView.vue used for its own FE-4b rebuild: load a
// larger page once and filter client-side (listClients takes only
// page/size, no server-side search) — a tenant's client count is expected
// to be small, same reasoning as resource servers.
//
// The record-id lookup control is gone: every row now links straight to
// TenantClientDetailView, so there is nothing left for it to do that a row
// click doesn't already cover.
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CreateClientDialog from '@/components/admin/CreateClientDialog.vue'
import { errorStateProps } from '@/api/problem'
import { listClients, type ClientView } from '@/api/tenantAdminClients'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

// FE-3a/FE-4b precedent: no server-side search exists, so load a larger
// page once and filter client-side rather than leave DataTable's search box
// silently non-functional.
const LIST_PAGE_SIZE = 200

const isWizardOpen = ref(false)

const page = ref(0)
const pageData = ref<PageView<ClientView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)
const searchQuery = ref('')

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listClients(slug, page.value, LIST_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default: {
      pageData.value = null
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

onMounted(load)
watch(page, load)

// The wizard navigates away to the credentials handoff view on success
// (CreateClientDialog owns that whole flow) — but a cancelled dialog still
// leaves this list stale if the tenant-admin registered a client in another
// tab, so refresh whenever the dialog closes, same as the create dialogs
// elsewhere refresh unconditionally on success.
function handleWizardOpenChange(open: boolean): void {
  isWizardOpen.value = open
  if (!open) {
    page.value = 0
    void load()
  }
}

const hasActiveFilters = computed(() => searchQuery.value.trim().length > 0)

// FE-6.1: this list is filtered CLIENT-SIDE over one loaded page
// (LIST_PAGE_SIZE, see the header comment) — if the tenant's real client
// count exceeds it, a search here can never see the rest. Honest about that
// scope rather than silently presenting a partial search as complete —
// same principle FE-5.2 established for the audit log's filters.
const searchScopeLimited = computed(
  () => hasActiveFilters.value && (pageData.value?.totalPages ?? 0) > 1,
)

const filteredItems = computed<ClientView[]>(() => {
  const items = pageData.value?.items ?? []
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return items
  return items.filter(
    (client) =>
      client.clientName.toLowerCase().includes(q) || client.clientId.toLowerCase().includes(q),
  )
})

function openDetail(client: ClientView): void {
  void router.push({ name: 'tenant-admin-client-detail', params: { slug, clientId: client.id } })
}

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

const columns = computed<ColumnDef<ClientView, unknown>[]>(() => [
  {
    id: 'name',
    header: 'Name',
    cell: ({ row }) => row.original.clientName,
  },
  {
    id: 'clientId',
    header: 'Client ID',
    cell: ({ row }) => h(IdentifierChip, { kind: 'client', value: row.original.clientId }),
  },
  {
    id: 'type',
    header: 'Type',
    cell: ({ row }) =>
      h(
        Badge,
        { variant: row.original.publicClient ? 'outline' : 'secondary' },
        { default: () => (row.original.publicClient ? 'Public' : 'Confidential') },
      ),
  },
  {
    id: 'grantTypes',
    header: 'Grant types',
    cell: ({ row }) => row.original.grantTypes.join(', ') || '—',
  },
  {
    id: 'created',
    header: 'Created',
    cell: ({ row }) => h(RelativeTime, { value: row.original.createdAt }),
  },
])
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Clients</h1>
        <p class="text-sm text-muted-foreground">
          Register OAuth2 clients for applications that authenticate against this tenant.
        </p>
      </div>
      <Button id="open-create-client-wizard" @click="isWizardOpen = true">Register a client</Button>
    </div>

    <p
      v-if="searchScopeLimited"
      id="clients-search-scope-notice"
      class="text-xs text-muted-foreground"
    >
      Searching only this page's {{ pageData?.items.length ?? 0 }} clients — there may be more on
      other pages.
    </p>

    <DataTable
      :columns="columns"
      :data="filteredItems"
      :row-key="(client: ClientView) => client.id"
      :state="dataTableState"
      :row-attrs="(client: ClientView) => ({ 'data-client-id': client.id })"
      :on-row-click="openDetail"
      :page="pageData?.page ?? page"
      :size="pageData?.size ?? LIST_PAGE_SIZE"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      :error-retryable="errorRetryable"
      :has-active-filters="hasActiveFilters"
      empty-title="No clients yet."
      empty-description="Register one to get started."
      filtered-empty-title="No clients match your search."
      filtered-empty-description="Try a different name or client id."
      search-placeholder="Search by name or client id…"
      @update:page="(p: number) => (page = p)"
      @update:search="(q: string) => (searchQuery = q)"
      @retry="load"
    >
      <template #action>
        <Button size="sm" @click="isWizardOpen = true">Register a client</Button>
      </template>
    </DataTable>

    <CreateClientDialog :open="isWizardOpen" :slug="slug" @update:open="handleWizardOpenChange" />
  </div>
</template>
