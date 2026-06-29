<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Activity,
  CheckCircle2,
  Copy,
  Database,
  Filter,
  MoreHorizontal,
  Plus,
  Radio,
  Search,
  Timer,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { clientsMock } from '@/api/mocks/clientsMocks'
import { useAdminStore } from '@/stores/admin'
import { useToast } from '@/composables/useToast'
import type { ClientStatus, ClientType } from '@/api/models'

const { toast } = useToast()

const adminStore = useAdminStore()

onMounted(async () => {
  await adminStore.fetchClients()
})

// Use API data if available, else mock
const data = computed(() => adminStore.clientsData ?? clientsMock)
const clients = computed(() => data.value.clients)
const metricStats = computed(() => data.value.stats)
const isLoading = computed(() => adminStore.clientsLoading)

// ── State ──────────────────────────────────────────────────────────────────────
const search       = ref('')
const activeFilter = ref<'All' | ClientStatus>('All')

const filteredClients = computed(() => {
  const q = search.value.toLowerCase()
  return clients.value.filter((c) => {
    const matchesSearch = !q || c.name.toLowerCase().includes(q) || c.clientId.toLowerCase().includes(q)
    const matchesFilter = activeFilter.value === 'All' || c.status === activeFilter.value
    return matchesSearch && matchesFilter
  })
})

// ── Actions ────────────────────────────────────────────────────────────────────
const copyId = (id: string) => {
  navigator.clipboard.writeText(id).then(() => {
    toast({ title: 'Copied', description: 'Client ID copied to clipboard.' })
  }).catch(() => {})
}

// ── Style maps ─────────────────────────────────────────────────────────────────
const typeBadgeClass: Record<ClientType, string> = {
  Confidential: 'bg-blue-500/10 text-blue-600 dark:text-blue-400 ring-1 ring-inset ring-blue-500/20',
  Public:       'bg-muted text-muted-foreground ring-1 ring-inset ring-border',
}

const statusConfig: Record<ClientStatus, { dot: string; pill: string }> = {
  Active:   { dot: 'bg-emerald-500 shadow-[0_0_6px_1px_rgba(16,185,129,0.45)]', pill: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-1 ring-inset ring-emerald-500/20' },
  Inactive: { dot: 'bg-zinc-300 dark:bg-zinc-600',  pill: 'bg-muted text-muted-foreground ring-1 ring-inset ring-border' },
}

// ── Metric cards data ──────────────────────────────────────────────────────────
const metricIcons  = [CheckCircle2, Timer, Radio]
const metricStyles = [
  { iconClass: 'text-emerald-600', iconBg: 'bg-emerald-50', accentBar: 'bg-emerald-500' },
  { iconClass: 'text-blue-600',    iconBg: 'bg-blue-50',    accentBar: 'bg-blue-500'    },
  { iconClass: 'text-violet-600',  iconBg: 'bg-violet-50',  accentBar: 'bg-violet-500'  },
]
const metrics = computed(() =>
  data.value.metrics.map((m, i) => ({
    ...m,
    icon: metricIcons[i],
    ...metricStyles[i],
  })),
)
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 space-y-8 font-sans">

    <!-- ── Page Header ── -->
    <header class="flex items-center justify-between animate-fade-up">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight text-foreground">Clients</h1>
        <p class="text-sm text-muted-foreground mt-1">
          Manage your registered OAuth2 applications.
        </p>
      </div>
      <RouterLink to="/admin/clients/new">
        <Button variant="default" size="sm" class="h-9 px-4 font-semibold gap-1.5 shadow-sm">
          <Plus class="h-4 w-4" aria-hidden="true" />
          New client
        </Button>
      </RouterLink>
    </header>

    <!-- ── Loading Skeleton ── -->
    <template v-if="isLoading && !adminStore.clientsData">
      <div class="space-y-4 pt-4">
        <div class="flex items-center gap-2.5">
          <div class="skeleton h-9 w-72 rounded-md" />
          <div class="skeleton h-9 w-20 rounded-md" />
        </div>
        <div class="rounded-xl border border-border bg-card overflow-hidden">
          <div class="bg-muted/30 h-10 border-b border-border" />
          <div v-for="n in 5" :key="n" class="flex items-center gap-4 px-5 py-4 border-b border-border/40">
            <div class="skeleton h-4 w-32" />
            <div class="skeleton h-4 w-40" />
            <div class="skeleton h-4 w-16" />
            <div class="skeleton h-4 w-16" />
            <div class="skeleton h-4 w-12" />
            <div class="skeleton h-4 w-20" />
          </div>
        </div>
      </div>
    </template>

    <template v-else>
    <!-- ── Toolbar ── -->
    <div class="flex items-center gap-2.5 animate-fade-up stagger-1">
      <!-- Search -->
      <div class="relative w-72">
        <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" aria-hidden="true" />
        <Input
          v-model="search"
          class="pl-9 h-9 text-sm bg-muted/40 border-border/60 placeholder:text-muted-foreground/50"
          placeholder="Search by name or client ID…"
          aria-label="Search clients"
        />
      </div>

      <!-- Filter dropdown -->
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button
            variant="outline"
            size="sm"
            class="h-9 gap-2 text-sm font-medium border-border/60"
            :class="activeFilter !== 'All' ? 'bg-foreground/5 text-foreground border-foreground/20' : 'text-muted-foreground hover:text-foreground'"
          >
            <Filter class="h-3.5 w-3.5" />
            {{ activeFilter === 'All' ? 'Filter' : activeFilter }}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" class="w-32">
          <DropdownMenuItem
            v-for="opt in ['All', 'Active', 'Inactive']"
            :key="opt"
            class="text-sm gap-2 cursor-pointer"
            :class="activeFilter === opt ? 'font-semibold text-foreground' : 'text-muted-foreground'"
            @click="activeFilter = opt as typeof activeFilter"
          >
            {{ opt }}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <div class="ml-auto flex items-center gap-2.5">
        <span class="text-xs font-medium text-muted-foreground tabular-nums">
          {{ filteredClients.length }} client{{ filteredClients.length !== 1 ? 's' : '' }}
        </span>
      </div>
    </div>

    <!-- ── Table Card ── -->
    <div class="rounded-xl border border-border/70 bg-card shadow-sm overflow-hidden animate-fade-up stagger-2" role="region" aria-label="Clients table">
      <table class="w-full border-collapse">
        <thead>
          <tr class="bg-muted/30 border-b border-border/60">
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Application</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Client ID</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Type</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Status</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Requests Today</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60 whitespace-nowrap">Last Used</th>
            <th class="px-5 py-3 w-10" />
          </tr>
        </thead>

        <tbody class="divide-y divide-border/40">
          <tr
            v-for="(client, i) in filteredClients"
            :key="client.id"
            class="group hover:bg-muted/20 transition-colors duration-100"
            :class="client.status === 'Inactive' ? 'opacity-45 hover:opacity-60' : ''"
          >
            <td class="px-5 py-4">
              <p class="text-sm font-semibold text-foreground leading-tight">{{ client.name }}</p>
              <p class="text-xs text-muted-foreground/60 mt-0.5">Created {{ client.createdAt }}</p>
            </td>

            <td class="px-5 py-4">
              <div class="flex items-center gap-1.5">
                <code class="font-mono text-[10.5px] text-muted-foreground bg-muted/60 px-1.5 py-0.5 rounded-md border border-border/30 tracking-tight">
                  {{ client.clientId }}
                </code>
                <button
                  class="opacity-0 group-hover:opacity-100 transition-opacity"
                  title="Copy client ID"
                  @click="copyId(client.clientId)"
                >
                  <Copy class="h-3 w-3 text-muted-foreground/50 hover:text-muted-foreground transition-colors" />
                </button>
              </div>
            </td>

            <td class="px-5 py-4">
              <span class="inline-block text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md" :class="typeBadgeClass[client.type]">
                {{ client.type }}
              </span>
            </td>

            <td class="px-5 py-4">
              <span class="inline-flex items-center gap-1.5 text-xs font-semibold px-2 py-0.5 rounded-md" :class="statusConfig[client.status].pill">
                <span class="h-1.5 w-1.5 rounded-full shrink-0" :class="statusConfig[client.status].dot" />
                {{ client.status }}
              </span>
            </td>

            <td class="px-5 py-4">
              <span class="text-sm font-bold text-foreground tabular-nums">{{ client.requestsToday }}</span>
            </td>

            <td class="px-5 py-4">
              <span class="text-xs font-medium text-muted-foreground/60">{{ client.lastUsed }}</span>
            </td>

            <td class="px-4 py-4 text-right">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <Button variant="ghost" size="icon" class="h-7 w-7 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity hover:bg-muted">
                    <MoreHorizontal class="h-3.5 w-3.5 text-muted-foreground" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" class="w-40 text-sm">
                  <DropdownMenuItem class="text-sm cursor-pointer">View details</DropdownMenuItem>
                  <DropdownMenuItem class="text-sm cursor-pointer">Edit</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem class="text-sm text-red-500 focus:text-red-600 focus:bg-red-50 cursor-pointer">Delete</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </td>
          </tr>

          <tr v-if="filteredClients.length === 0">
            <td colspan="7">
              <div class="flex flex-col items-center justify-center py-20 gap-2.5">
                <div class="h-12 w-12 rounded-xl bg-muted/50 flex items-center justify-center border border-border/50">
                  <Database class="h-5 w-5 text-muted-foreground/40" />
                </div>
                <p class="text-sm font-semibold text-foreground mt-1">No clients found</p>
                <p class="text-xs text-muted-foreground/60">Try adjusting your search or filter.</p>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ── Metric Cards ── -->
    <section aria-label="Client performance metrics" class="grid grid-cols-1 sm:grid-cols-3 gap-4">
      <div
        v-for="card in metrics"
        :key="card.label"
        class="relative bg-card border border-border rounded-xl p-5 shadow-sm overflow-hidden flex items-start gap-4 hover:shadow-md transition-shadow duration-200"
      >
        <!-- Top accent bar -->
        <div class="absolute top-0 left-5 right-5 h-[2px] rounded-b-full opacity-70" :class="card.accentBar" />

        <!-- Icon -->
        <div class="h-10 w-10 rounded-lg flex items-center justify-center shrink-0 mt-0.5" :class="card.iconBg">
          <component :is="card.icon" class="h-5 w-5" :class="card.iconClass" />
        </div>

        <!-- Text -->
        <div class="min-w-0 flex-1">
          <p class="text-xs font-semibold uppercase tracking-widest text-muted-foreground/60">{{ card.label }}</p>
          <p class="text-3xl font-bold text-foreground mt-1.5 leading-none tabular-nums">{{ card.value }}</p>
          <p class="text-xs font-medium text-muted-foreground/60 mt-2 flex items-center gap-1.5">
            <span v-if="card.trend === 'live'" class="inline-block h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse shrink-0" />
            <Activity v-else-if="card.trend === 'up'" class="h-3 w-3 text-emerald-600 shrink-0" />
            {{ card.sub }}
          </p>
        </div>
      </div>
    </section>
    </template>
  </div>
</template>

<style scoped>
@media (prefers-reduced-motion: reduce) {
  .animate-fade-up,
  .animate-pulse {
    animation: none !important;
  }
}
</style>
