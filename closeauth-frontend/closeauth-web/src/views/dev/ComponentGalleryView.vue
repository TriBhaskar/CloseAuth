<script setup lang="ts">
// FE-1 checkpoint (build plan): "a components gallery route (dev-only)
// rendering every primitive in every state, in all three theme settings and
// at 375px and 1440px, with the theme lint gate green." Dev-only by
// construction — see router/index.ts, where this route only exists when
// import.meta.env.DEV is true, so it's absent from a production build
// (not just hidden behind a runtime check that could be bypassed).
import { ref } from 'vue'
import { Monitor, Moon, Sun } from 'lucide-vue-next'
import type { ColumnDef } from '@tanstack/vue-table'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import IdentifierChip, { type ChipKind } from '@/components/common/IdentifierChip.vue'
import StateBadge, {
  tenantStatusTone,
  userStatusTone,
  type Tone,
} from '@/components/common/StateBadge.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import CopyButton from '@/components/common/CopyButton.vue'
import JsonViewer from '@/components/common/JsonViewer.vue'
import DataTable from '@/components/common/DataTable.vue'
import FormField from '@/components/common/FormField.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import TypedConfirmDialog from '@/components/common/TypedConfirmDialog.vue'
import SecretRevealPanel from '@/components/common/SecretRevealPanel.vue'
import { useThemeStore } from '@/stores/theme'
import { errorStateProps, type AdminResult } from '@/api/problem'

const themeStore = useThemeStore()

// ---- DataTable: a synthetic dataset, toggleable through all five states ----
interface GalleryUser {
  id: string
  name: string
  status: 'ACTIVE' | 'PENDING' | 'SUSPENDED' | 'DELETED'
}
const GALLERY_ROWS: GalleryUser[] = [
  { id: 'u1', name: 'Ada Lovelace', status: 'ACTIVE' },
  { id: 'u2', name: 'Grace Hopper', status: 'PENDING' },
  { id: 'u3', name: 'Alan Turing', status: 'SUSPENDED' },
]
const galleryColumns: ColumnDef<GalleryUser, unknown>[] = [
  { id: 'name', header: 'Name', accessorKey: 'name', enableSorting: true },
  {
    id: 'status',
    header: 'Status',
    accessorKey: 'status',
    enableSorting: false,
    cell: (info) => info.getValue(),
  },
]
const tableState = ref<'loading' | 'error' | 'loaded'>('loaded')
const tableHasData = ref(true)
const tableHasFilters = ref(false)

// ---- Dialogs: trigger buttons to demo the dialog-family components ----
const confirmOpen = ref(false)
const typedConfirmOpen = ref(false)
const secretPanelOpen = ref(false)

// ---- FormField ----
const formFieldValue = ref('')
const formFieldError = ref('')

const CHIP_KINDS: Array<{ kind: ChipKind; value: string }> = [
  { kind: 'tenant', value: 'ten_acme-inc-7f3a' },
  { kind: 'user', value: 'a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c' },
  { kind: 'client', value: 'admin-console-acme' },
  { kind: 'resource-server', value: 'rs_orders-api-9k2m' },
  { kind: 'scope', value: 'orders:write' },
  { kind: 'session', value: 'sess_8f3a2b1c9d0e' },
  { kind: 'role', value: 'role_tenant-admin' },
]

const TONES: Tone[] = ['warn', 'ok', 'danger', 'muted', 'accent']

const NOW = new Date()
const RELATIVE_SAMPLES = [
  new Date(NOW.getTime() - 30 * 1000).toISOString(),
  new Date(NOW.getTime() - 2 * 60 * 60 * 1000).toISOString(),
  new Date(NOW.getTime() - 3 * 24 * 60 * 60 * 1000).toISOString(),
  new Date(NOW.getTime() + 5 * 60 * 1000).toISOString(),
]

const SAMPLE_JSON = {
  eventType: 'USER_SUSPENDED',
  actor: { kind: 'user', id: 'a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c' },
  target: { kind: 'user', id: 'b2c3d4e5-f6a7-48b9-0c1d-2e3f4a5b6c7d' },
  outcome: 'success',
}

// FE-1.11 (spec §7.3): synthetic AdminResult error-kind objects, one per
// category — not real API calls, since the point is proving the RFC 7807
// -> UI mapping (api/problem.ts's errorStateProps/describeAdminError)
// actually renders, not exercising a network path.
type ErrorResult = Extract<AdminResult<unknown>, { kind: 'error' }>
const ERROR_CATEGORY_SAMPLES: Array<{ label: string; result: ErrorResult }> = [
  {
    label: '403 forbidden',
    result: {
      kind: 'error',
      status: 403,
      code: 'access_denied',
      message: 'nope',
      category: 'forbidden',
    },
  },
  {
    label: '404 not found',
    result: {
      kind: 'error',
      status: 404,
      code: 'user.not_found',
      message: 'No such user.',
      category: 'notFound',
    },
  },
  {
    label: '429 rate limited',
    result: {
      kind: 'error',
      status: 429,
      code: 'rate_limited',
      message: 'slow down',
      category: 'rateLimited',
    },
  },
  {
    label: '5xx server',
    result: {
      kind: 'error',
      status: 500,
      code: 'internal_error',
      message: 'Something broke.',
      category: 'server',
    },
  },
  {
    label: 'network unreachable',
    result: { kind: 'error', status: 503, code: '', message: 'unreachable', category: 'network' },
  },
]
</script>

<template>
  <div class="min-h-screen bg-canvas text-ink p-8 flex flex-col gap-10 max-w-4xl mx-auto">
    <header class="flex items-center justify-between border-b border-line pb-4">
      <h1 class="text-page-title font-semibold">Component gallery</h1>
      <Button
        variant="ghost"
        size="sm"
        :aria-label="`Theme: ${themeStore.mode} — click to switch`"
        @click="themeStore.cycle()"
      >
        <Sun v-if="themeStore.mode === 'light'" class="size-4" />
        <Moon v-else-if="themeStore.mode === 'dark'" class="size-4" />
        <Monitor v-else class="size-4" />
        {{ themeStore.mode }}
      </Button>
    </header>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">IdentifierChip — one per kind</h2>
      <div class="flex flex-wrap gap-3">
        <IdentifierChip v-for="c in CHIP_KINDS" :key="c.kind" :kind="c.kind" :value="c.value" />
      </div>
      <div class="flex flex-wrap gap-3">
        <span class="text-meta text-ink-muted">with href:</span>
        <IdentifierChip
          kind="role"
          value="role_tenant-admin"
          href="/t/acme/console/roles/role_tenant-admin"
        />
      </div>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">StateBadge — every tone</h2>
      <div class="flex flex-wrap gap-2">
        <StateBadge v-for="t in TONES" :key="t" :tone="t" :label="t" />
      </div>
      <p class="text-meta text-ink-muted">
        muted has no fill — border only, everything else above turns confetti.
      </p>
      <div class="flex flex-wrap gap-2">
        <StateBadge :tone="tenantStatusTone('PROVISIONING')" label="PROVISIONING" />
        <StateBadge :tone="tenantStatusTone('ACTIVE')" label="ACTIVE" />
        <StateBadge :tone="tenantStatusTone('SUSPENDED')" label="SUSPENDED" />
        <StateBadge :tone="tenantStatusTone('DELETED')" label="DELETED" />
        <StateBadge :tone="userStatusTone('PENDING')" label="PENDING" />
      </div>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">EmptyState</h2>
      <div class="border border-line rounded-lg">
        <EmptyState title="No tenants yet" description="Provision one to get started.">
          <template #action>
            <Button size="sm">Provision tenant</Button>
          </template>
        </EmptyState>
      </div>
      <div class="border border-line rounded-lg">
        <EmptyState
          title="No events match these filters"
          description="Try widening the date range."
        />
      </div>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">ErrorState — §7.3 category mapping</h2>
      <div v-for="s in ERROR_CATEGORY_SAMPLES" :key="s.label" class="border border-line rounded-lg">
        <p class="text-meta text-ink-muted px-3 pt-2">
          {{ s.label }} — retryable: {{ errorStateProps(s.result).retryable }}
        </p>
        <ErrorState
          :message="errorStateProps(s.result).message"
          :trace-id="errorStateProps(s.result).traceId"
        />
      </div>
      <p class="text-meta text-ink-muted">
        trace_id doesn't exist end-to-end yet (tracked backend dependency) — shown here for layout
        only.
      </p>
      <div class="border border-line rounded-lg">
        <ErrorState message="Something went wrong on our end." trace-id="tr_9f3c1a8b2e4d" />
      </div>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">RelativeTime</h2>
      <ul class="flex flex-col gap-1 text-body">
        <li v-for="v in RELATIVE_SAMPLES" :key="v" class="flex gap-2">
          <RelativeTime :value="v" />
        </li>
      </ul>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">CopyButton</h2>
      <CopyButton
        value="ten_acme-inc"
        class="border border-line rounded px-2 py-1 text-body w-fit"
      />
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">JsonViewer</h2>
      <JsonViewer :value="SAMPLE_JSON" />
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">FormField</h2>
      <FormField
        id="gallery-field"
        label="Email"
        :error="formFieldError"
        hint="A hint, replaced by the error above it when one is set."
      >
        <template #default="{ hasError, describedBy }">
          <Input
            id="gallery-field"
            v-model="formFieldValue"
            :aria-invalid="hasError"
            :aria-describedby="describedBy"
          />
        </template>
      </FormField>
      <Button
        size="sm"
        variant="outline"
        class="w-fit"
        @click="formFieldError = formFieldError ? '' : 'That email is already taken.'"
      >
        Toggle error
      </Button>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">
        DataTable — all five states, sort, paging, mobile card layout
      </h2>
      <div class="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" @click="tableState = 'loading'">loading</Button>
        <Button size="sm" variant="outline" @click="tableState = 'error'">error</Button>
        <Button
          size="sm"
          variant="outline"
          @click="((tableState = 'loaded'), (tableHasData = false), (tableHasFilters = false))"
          >empty (first-run)</Button
        >
        <Button
          size="sm"
          variant="outline"
          @click="((tableState = 'loaded'), (tableHasData = false), (tableHasFilters = true))"
          >empty (filtered)</Button
        >
        <Button
          size="sm"
          variant="outline"
          @click="((tableState = 'loaded'), (tableHasData = true))"
          >loaded</Button
        >
      </div>
      <DataTable
        :columns="galleryColumns"
        :data="tableHasData ? GALLERY_ROWS : []"
        :row-key="(r: GalleryUser) => r.id"
        :state="tableState"
        :page="0"
        :size="20"
        :total-elements="tableHasData ? GALLERY_ROWS.length : 0"
        :total-pages="1"
        :has-active-filters="tableHasFilters"
        empty-title="No users yet"
        empty-description="Invite one to get started."
        filtered-empty-title="No results match these filters"
        filtered-empty-description="Try clearing your search."
        error-message="Could not reach the backend."
      >
        <template #card="{ row }">
          <div class="flex items-center justify-between">
            <span>{{ (row as GalleryUser).name }}</span>
            <StateBadge
              :tone="userStatusTone((row as GalleryUser).status)"
              :label="(row as GalleryUser).status"
            />
          </div>
        </template>
      </DataTable>
      <p class="text-meta text-ink-muted">
        Resize the window below 768px to see the stacked-card layout.
      </p>
    </section>

    <section class="flex flex-col gap-3">
      <h2 class="text-section-title font-semibold">Dialog family</h2>
      <div class="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" @click="confirmOpen = true">Open ConfirmDialog</Button>
        <Button size="sm" variant="outline" @click="typedConfirmOpen = true"
          >Open TypedConfirmDialog</Button
        >
        <Button size="sm" variant="outline" @click="secretPanelOpen = true"
          >Open SecretRevealPanel</Button
        >
      </div>
      <ConfirmDialog
        :open="confirmOpen"
        title="Suspend Acme Inc?"
        description="Users won't be able to sign in and all active tokens stop working immediately."
        @update:open="confirmOpen = $event"
        @confirm="confirmOpen = false"
      />
      <TypedConfirmDialog
        v-model:open="typedConfirmOpen"
        title="Delete Acme Inc?"
        description="This is permanent. All tenant data is removed and cannot be recovered."
        match-text="ten_acme-inc"
        @confirm="typedConfirmOpen = false"
      />
      <SecretRevealPanel
        :open="secretPanelOpen"
        title="Client registered"
        warning-message="These credentials are shown once. Once you leave this page, the secret cannot be retrieved again."
        :fields="[
          {
            id: 'client-id',
            label: 'client_id',
            value: 'admin-console-acme',
            hint: 'Put this in your app config.',
          },
          {
            id: 'secret',
            label: 'client_secret',
            value: 'sk_live_9f3c1a8b2e4d7f6a',
            maskable: true,
          },
        ]"
        @continue="secretPanelOpen = false"
      />
    </section>

    <section class="flex flex-col gap-2 border-t border-line pt-6">
      <h2 class="text-section-title font-semibold">Shells</h2>
      <p class="text-meta text-ink-muted">
        AuthShell/EntryShell/ConsoleShell are full-page layouts, not meaningfully embeddable inline
        in this gallery alongside everything above (a nested sidebar-within-a-page reads as broken,
        not as a demo). Exercise them directly: EntryShell at <code class="font-mono">/</code>,
        AuthShell at <code class="font-mono">/login</code>, ConsoleShell at
        <code class="font-mono">/t/acme/console</code> (tenant, with the icon-rail/Sheet
        breakpoints) and <code class="font-mono">/platform/console</code> (platform, no tenant
        identity chip).
      </p>
    </section>
  </div>
</template>
