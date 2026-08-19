<script setup lang="ts">
// FE-4b (spec §6.4.4): rebuilt onto DataTable — this view's first adoption
// of the FE-1 component library (Stage UI-3c built it against raw
// ui/table + QueryState + AdminPagination, the same starting point Users
// was in before FE-4a). Columns follow spec's literal order: Name · Slug
// chip · Audience URI (mono, truncated, copyable) · Scope count · Created.
// The Source badge (auto-created-with-a-client vs standalone) isn't in
// spec's own column list but is kept — real, already-correct information
// (an auto-created RS can't be deleted directly; the detail page explains
// why) that a spec rebuild shouldn't quietly drop.
//
// The Audience URI column deliberately does NOT use IdentifierChip: an
// audience is a URL, not an opaque identifier, and IdentifierChip's
// middle-truncation + "{kind} ID {value}" aria-label are built for
// ID-shaped values (UUIDs, slugs). Plain mono text (CSS-truncated) +
// CopyButton satisfies spec's literal "mono, truncated, copyable" without
// misusing the ID-chip semantics — the Slug column gets the chip instead.
//
// Search: TenantResourceServerController's list endpoint takes only
// page/size, same as before this rebuild — no server-side search exists.
// DataTable always renders a search box, so leaving it unwired would be a
// control that silently does nothing when typed into. Same fix FE-3a used
// for the platform tenant list under the identical constraint: load a
// larger page once and filter client-side (searchQuery/filteredItems
// below) — a tenant's resource-server count is expected to be small.
import { computed, h, onMounted, reactive, ref, watch, type VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import DataTable, { type ColumnDef } from '@/components/common/DataTable.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CopyButton from '@/components/common/CopyButton.vue'
import FormField from '@/components/common/FormField.vue'
import { describeAdminError } from '@/api/problem'
import {
  createResourceServer,
  listResourceServers,
  RESOURCE_SERVER_CONFLICT_FIELDS,
  type ResourceServerView,
} from '@/api/tenantAdminResourceServers'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

// FE-3a precedent: no real server-side search exists, so load a larger page
// once and filter client-side rather than leave DataTable's search box
// silently non-functional.
const LIST_PAGE_SIZE = 200

const page = ref(0)
const pageData = ref<PageView<ResourceServerView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const searchQuery = ref('')

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listResourceServers(slug, page.value, LIST_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)
watch(page, load)

const hasActiveFilters = computed(() => searchQuery.value.trim().length > 0)

const filteredItems = computed<ResourceServerView[]>(() => {
  const items = pageData.value?.items ?? []
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return items
  return items.filter((rs) => rs.name.toLowerCase().includes(q) || rs.slug.toLowerCase().includes(q))
})

function openDetail(rs: ResourceServerView): void {
  void router.push({ name: 'tenant-admin-resource-server-detail', params: { slug, rsId: rs.id } })
}

const dataTableState = computed<'loading' | 'error' | 'loaded'>(() => {
  if (isLoading.value) return 'loading'
  if (errorMessage.value) return 'error'
  return 'loaded'
})

// ---- create dialog ---------------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const form = reactive({ slug: '', name: '', audienceIdentifier: '' })
const errors = reactive<Record<string, string>>({})
const banner = ref('')

function resetForm(): void {
  form.slug = ''
  form.name = ''
  form.audienceIdentifier = ''
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]
}

function handleOpenChange(open: boolean): void {
  isCreateOpen.value = open
  if (!open) resetForm()
}

async function handleCreate(): Promise<void> {
  if (isCreating.value) return
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]

  isCreating.value = true
  try {
    const result = await createResourceServer(slug, {
      slug: form.slug,
      name: form.name,
      audienceIdentifier: form.audienceIdentifier,
    })
    switch (result.kind) {
      case 'ok':
        isCreateOpen.value = false
        resetForm()
        page.value = 0
        await load()
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
        break
      case 'conflict': {
        const field = RESOURCE_SERVER_CONFLICT_FIELDS[result.code]
        if (field) {
          errors[field] = result.message
        } else {
          banner.value = result.message
        }
        break
      }
      case 'reauth':
        break
      default:
        banner.value = describeAdminError(result)
        break
    }
  } finally {
    isCreating.value = false
  }
}

// ---- DataTable columns ----------------------------------------------------

const columns = computed<ColumnDef<ResourceServerView, unknown>[]>(() => [
  {
    id: 'name',
    header: 'Name',
    cell: ({ row }) => row.original.name,
  },
  {
    id: 'slug',
    header: 'Slug',
    cell: ({ row }) => h(IdentifierChip, { kind: 'resource-server', value: row.original.slug }),
  },
  {
    id: 'audience',
    header: 'Audience URI',
    cell: ({ row }): VNode =>
      h('div', { class: 'flex items-center gap-2 max-w-xs' }, [
        h('code', { class: 'font-mono text-xs truncate' }, row.original.audienceIdentifier),
        h(
          CopyButton,
          { value: row.original.audienceIdentifier, class: 'text-xs text-muted-foreground shrink-0 hover:text-foreground' },
        ),
      ]),
  },
  {
    id: 'scopeCount',
    header: 'Scope count',
    cell: ({ row }) => row.original.scopeCount ?? '—',
  },
  {
    id: 'source',
    header: 'Source',
    cell: ({ row }) =>
      h(Badge, { variant: row.original.autoCreated ? 'secondary' : 'outline' },
        { default: () => (row.original.autoCreated ? 'Created with a client' : 'Standalone') }),
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
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Resource servers</h1>
        <p class="text-sm text-muted-foreground">Manage this tenant's resource servers and their scope catalogs.</p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleOpenChange">
        <DialogTrigger as-child>
          <Button id="new-rs-button">New resource server</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a resource server</DialogTitle>
            <DialogDescription>
              A standalone resource server, independent of any client. Slug and audience must be unique.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <FormField id="new-rs-name" label="Name" :error="errors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-name"
                  v-model="form.name"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField id="new-rs-slug" label="Slug" :error="errors.slug" hint="Lowercase letters, digits, hyphens. Used in scope prefixes.">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-slug"
                  v-model="form.slug"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField
              id="new-rs-audience"
              label="Audience identifier"
              :error="errors.audienceIdentifier"
              hint="Placed in the token aud claim. Cannot be changed after creation."
            >
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-audience"
                  v-model="form.audienceIdentifier"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">
                {{ isCreating ? 'Creating…' : 'Create resource server' }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <DataTable
      :columns="columns"
      :data="filteredItems"
      :row-key="(rs: ResourceServerView) => rs.id"
      :state="dataTableState"
      :row-attrs="(rs: ResourceServerView) => ({ 'data-resource-server-id': rs.id })"
      :on-row-click="openDetail"
      :page="pageData?.page ?? page"
      :size="pageData?.size ?? LIST_PAGE_SIZE"
      :total-elements="pageData?.totalElements ?? 0"
      :total-pages="pageData?.totalPages ?? 0"
      :error-message="errorMessage ?? undefined"
      :has-active-filters="hasActiveFilters"
      empty-title="No resource servers yet."
      empty-description="Create one to get started."
      filtered-empty-title="No resource servers match your search."
      filtered-empty-description="Try a different name or slug."
      search-placeholder="Search by name or slug…"
      @update:page="(p: number) => (page = p)"
      @update:search="(q: string) => (searchQuery = q)"
      @retry="load"
    >
      <template #action>
        <Button size="sm" @click="isCreateOpen = true">New resource server</Button>
      </template>
    </DataTable>
  </div>
</template>
