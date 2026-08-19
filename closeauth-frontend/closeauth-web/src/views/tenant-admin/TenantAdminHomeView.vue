<script setup lang="ts">
// Stage UI-3a foundation stub, rebuilt FE-4d (spec §6.4.1): the console's
// real landing page. Deliberately NOT a dashboard — no charts, no time
// series, no metrics (spec's own words). Two blocks per spec; only the
// first ships this session — four count tiles (Users/Clients/Resource
// servers/Roles), each a link to its section. A zero-count tile shows a
// "+ Add your first X" prompt instead of "0" so an empty tenant reads as an
// invitation, not a report of nothing. The recent-activity block is FE-5.8's
// job (needs the audit query, a later session) — this page doesn't fake one.
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
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { Component } from 'vue'
import { Users, KeyRound, ServerCog, ShieldEllipsis } from 'lucide-vue-next'
import { listUsers } from '@/api/tenantAdminUsers'
import { listResourceServers } from '@/api/tenantAdminResourceServers'
import { listRolesPaged } from '@/api/tenantAdminRoles'
import { getClientCount } from '@/api/tenantAdminClients'

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

onMounted(async () => {
  const [usersResult, clientsResult, rsResult, rolesResult] = await Promise.all([
    listUsers(slug, 0, 1),
    getClientCount(slug),
    listResourceServers(slug, 0, 1),
    listRolesPaged(slug, 0, 1),
  ])

  if (usersResult.kind === 'ok') setTile('users', { count: usersResult.value.totalElements, isLoading: false })
  else if (usersResult.kind !== 'reauth') setTile('users', { error: 'Could not load.', isLoading: false })

  if (clientsResult.kind === 'ok') setTile('clients', { count: clientsResult.value.count, isLoading: false })
  else if (clientsResult.kind !== 'reauth') setTile('clients', { error: 'Could not load.', isLoading: false })

  if (rsResult.kind === 'ok') setTile('resource-servers', { count: rsResult.value.totalElements, isLoading: false })
  else if (rsResult.kind !== 'reauth') setTile('resource-servers', { error: 'Could not load.', isLoading: false })

  if (rolesResult.kind === 'ok') setTile('roles', { count: rolesResult.value.totalElements, isLoading: false })
  else if (rolesResult.kind !== 'reauth') setTile('roles', { error: 'Could not load.', isLoading: false })
})

const displayTiles = computed(() => tiles.value)
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Overview</h1>
      <p class="text-sm text-muted-foreground">A quick look at this tenant.</p>
    </div>

    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <RouterLink
        v-for="tile in displayTiles"
        :key="tile.key"
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
        <template v-else-if="tile.error">
          <p role="alert" class="text-sm text-destructive">{{ tile.error }}</p>
        </template>
        <template v-else-if="tile.count === 0">
          <p class="text-sm font-medium text-foreground">+ {{ tile.createLabel }}</p>
        </template>
        <template v-else>
          <p class="text-3xl font-semibold tracking-tight">{{ tile.count }}</p>
        </template>
      </RouterLink>
    </div>
  </div>
</template>
