<script setup lang="ts">
// Stage UI-3a foundation stub, rebuilt FE-4d (spec §6.4.1): the console's
// real landing page. Deliberately NOT a dashboard — no charts, no time
// series, no metrics (spec's own words). Two blocks per spec — four count
// tiles (Users/Clients/Resource servers/Roles), each a link to its section,
// and (FE-5.8, below) the last 10 audit events. A zero-count tile shows a
// "+ Add your first X" prompt instead of "0" so an empty tenant reads as an
// invitation, not a report of nothing.
//
// Users/Resource servers/Roles counts come from existing list endpoints'
// totalElements (size=1, just enough to read the count without fetching
// real rows). Clients has no list at all (FE-4.10, still blocked) — FE-4d
// adds a dedicated count-only endpoint for exactly this tile, the smallest
// possible slice of that gap.
//
// The old ping-status widget (api/tenantAdminPing.ts) is retired — once
// every tile requires its own genuine authenticated API call, a separate
// "prove the backend is reachable" widget is redundant, and the module had
// exactly one caller (this file).
//
// FE-5.8 (spec §6.4.1): the overview's second block — the last 10 audit
// events, same row shape TenantAuditView.vue renders, with a
// `View all activity ->` link to the full log. Loads via its OWN promise,
// never folded into the tiles' Promise.all below: "the tiles and the
// activity list load independently... the overview is never blocked by its
// least important section" (spec, verbatim). Killing the audit endpoint
// must leave all four tiles fully functional — see this file's own spec.
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { Component } from 'vue'
import { Users, KeyRound, ServerCog, ShieldEllipsis } from 'lucide-vue-next'
import { listUsers } from '@/api/tenantAdminUsers'
import { listResourceServers } from '@/api/tenantAdminResourceServers'
import { listRolesPaged } from '@/api/tenantAdminRoles'
import { getClientCount } from '@/api/tenantAdminClients'
import { listAuditEvents, describeAuditActor, type AuditEventView } from '@/api/tenantAdminAudit'
import { describeAdminError } from '@/api/problem'
import RelativeTime from '@/components/common/RelativeTime.vue'
import StateBadge, { auditOutcomeTone } from '@/components/common/StateBadge.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()
const slug = String(route.params.slug ?? '')

interface TileState {
  key: string
  label: string
  icon: Component
  path: string
  createLabel: string
  count: number | null
  isLoading: boolean
  error: string | null
}

const tiles = ref<TileState[]>([
  {
    key: 'users',
    label: 'Users',
    icon: Users,
    path: `/t/${slug}/console/users`,
    createLabel: 'Add your first user',
    count: null,
    isLoading: true,
    error: null,
  },
  {
    key: 'clients',
    label: 'Clients',
    icon: KeyRound,
    path: `/t/${slug}/console/clients`,
    createLabel: 'Register your first client',
    count: null,
    isLoading: true,
    error: null,
  },
  {
    key: 'resource-servers',
    label: 'Resource servers',
    icon: ServerCog,
    path: `/t/${slug}/console/resource-servers`,
    createLabel: 'Add your first resource server',
    count: null,
    isLoading: true,
    error: null,
  },
  {
    key: 'roles',
    label: 'Roles',
    icon: ShieldEllipsis,
    path: `/t/${slug}/console/roles`,
    createLabel: 'Add your first role',
    count: null,
    isLoading: true,
    error: null,
  },
])

function setTile(key: string, patch: Partial<TileState>): void {
  const tile = tiles.value.find((t) => t.key === key)
  if (tile) Object.assign(tile, patch)
}

// FE-6.1: each tile gets its OWN retryable loader — a failed tile previously
// had no recovery beyond a full page reload, and the tile doubled as both
// the error message AND a RouterLink navigation target (an error rendered
// as a clickable link). A retry now only re-fetches the one tile that
// failed, not the whole card grid.
async function loadUsersTile(): Promise<void> {
  setTile('users', { isLoading: true, error: null })
  const result = await listUsers(slug, 0, 1)
  if (result.kind === 'ok')
    setTile('users', { count: result.value.totalElements, isLoading: false })
  else if (result.kind !== 'reauth')
    setTile('users', { error: describeAdminError(result), isLoading: false })
}

async function loadClientsTile(): Promise<void> {
  setTile('clients', { isLoading: true, error: null })
  const result = await getClientCount(slug)
  if (result.kind === 'ok') setTile('clients', { count: result.value.count, isLoading: false })
  else if (result.kind !== 'reauth')
    setTile('clients', { error: describeAdminError(result), isLoading: false })
}

async function loadResourceServersTile(): Promise<void> {
  setTile('resource-servers', { isLoading: true, error: null })
  const result = await listResourceServers(slug, 0, 1)
  if (result.kind === 'ok')
    setTile('resource-servers', { count: result.value.totalElements, isLoading: false })
  else if (result.kind !== 'reauth')
    setTile('resource-servers', { error: describeAdminError(result), isLoading: false })
}

async function loadRolesTile(): Promise<void> {
  setTile('roles', { isLoading: true, error: null })
  const result = await listRolesPaged(slug, 0, 1)
  if (result.kind === 'ok')
    setTile('roles', { count: result.value.totalElements, isLoading: false })
  else if (result.kind !== 'reauth')
    setTile('roles', { error: describeAdminError(result), isLoading: false })
}

const TILE_LOADERS: Record<string, () => Promise<void>> = {
  users: loadUsersTile,
  clients: loadClientsTile,
  'resource-servers': loadResourceServersTile,
  roles: loadRolesTile,
}

function retryTile(key: string): void {
  void TILE_LOADERS[key]?.()
}

onMounted(() => {
  void Promise.all([loadUsersTile(), loadClientsTile(), loadResourceServersTile(), loadRolesTile()])
})

const displayTiles = computed(() => tiles.value)

// ---- recent activity (FE-5.8) — its own promise, independent of the tiles ----

const RECENT_ACTIVITY_SIZE = 10

const recentEvents = ref<AuditEventView[]>([])
const isActivityLoading = ref(true)
const activityErrorMessage = ref<string | null>(null)

async function loadRecentActivity(): Promise<void> {
  isActivityLoading.value = true
  activityErrorMessage.value = null
  const result = await listAuditEvents(slug, {}, 0, RECENT_ACTIVITY_SIZE)
  switch (result.kind) {
    case 'ok':
      recentEvents.value = result.value.items
      isActivityLoading.value = false
      break
    case 'reauth':
      break
    default:
      recentEvents.value = []
      activityErrorMessage.value = describeAdminError(result)
      isActivityLoading.value = false
      break
  }
}

onMounted(() => {
  void loadRecentActivity()
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Overview</h1>
      <p class="text-sm text-muted-foreground">A quick look at this tenant.</p>
    </div>

    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <template v-for="tile in displayTiles" :key="tile.key">
        <!-- FE-6.1: a failed tile is deliberately NOT the same clickable
             RouterLink as the loading/loaded states — an error message that
             also silently doubles as a navigation target is confusing, and
             a <button> Retry action nested inside an <a> is invalid HTML.
             This is a plain, non-navigating card with its own Retry. -->
        <div
          v-if="tile.error"
          :id="`overview-tile-${tile.key}`"
          class="rounded-xl border border-border p-6 flex flex-col gap-3"
        >
          <div class="flex items-center gap-2 text-muted-foreground">
            <component :is="tile.icon" class="h-4 w-4" />
            <span class="text-sm">{{ tile.label }}</span>
          </div>
          <p role="alert" class="text-sm text-destructive">{{ tile.error }}</p>
          <button
            type="button"
            class="self-start text-sm font-medium text-primary hover:underline"
            @click="retryTile(tile.key)"
          >
            Retry
          </button>
        </div>

        <RouterLink
          v-else
          :id="`overview-tile-${tile.key}`"
          :to="tile.path"
          class="rounded-xl border border-border p-6 flex flex-col gap-3 hover:border-primary/50 transition-colors"
        >
          <div class="flex items-center gap-2 text-muted-foreground">
            <component :is="tile.icon" class="h-4 w-4" />
            <span class="text-sm">{{ tile.label }}</span>
          </div>

          <template v-if="tile.isLoading">
            <div class="h-9 w-12 rounded bg-muted" />
          </template>
          <template v-else-if="tile.count === 0">
            <p class="text-sm font-medium text-foreground">+ {{ tile.createLabel }}</p>
          </template>
          <template v-else>
            <p class="text-3xl font-semibold tracking-tight">{{ tile.count }}</p>
          </template>
        </RouterLink>
      </template>
    </div>

    <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-semibold tracking-tight">Recent activity</h2>
        <RouterLink
          id="overview-view-all-activity"
          :to="`/t/${slug}/console/audit`"
          class="text-sm font-medium text-primary hover:underline"
        >
          View all activity &rarr;
        </RouterLink>
      </div>

      <template v-if="isActivityLoading">
        <div class="flex flex-col gap-2">
          <div v-for="i in 3" :key="i" class="skeleton h-8 w-full rounded-md" />
        </div>
      </template>
      <ErrorState
        v-else-if="activityErrorMessage"
        :message="activityErrorMessage"
        @retry="loadRecentActivity"
      />
      <EmptyState
        v-else-if="recentEvents.length === 0"
        title="No activity yet."
        description="Once something happens in this tenant, it shows up here."
      />
      <ul v-else class="flex flex-col divide-y divide-border">
        <li
          v-for="event in recentEvents"
          :key="event.id"
          :data-activity-event-id="event.id"
          class="flex items-center justify-between gap-4 py-2 text-sm"
        >
          <div class="flex items-center gap-3 min-w-0">
            <RelativeTime :value="event.createdAt" />
            <span class="font-mono text-xs truncate">{{ event.eventType }}</span>
            <span class="text-xs text-muted-foreground truncate">{{
              describeAuditActor(event)
            }}</span>
          </div>
          <StateBadge :tone="auditOutcomeTone(event.outcome)" :label="event.outcome" />
        </li>
      </ul>
    </div>
  </div>
</template>
