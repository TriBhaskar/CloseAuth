<script setup lang="ts">
import { computed, ref } from 'vue'
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

// ── Types ──────────────────────────────────────────────────────────────────────
type ClientType   = 'Confidential' | 'Public'
type ClientStatus = 'Active' | 'Inactive'

interface Client {
  id: string
  name: string
  created: string
  type: ClientType
  status: ClientStatus
  requests: string
  lastUsed: string
}

// ── Static data ────────────────────────────────────────────────────────────────
const clients: Client[] = [
  { id: 'ca_abc123def456', name: 'Web Application',    created: '3 months ago', type: 'Confidential', status: 'Active',   requests: '45,820', lastUsed: '2 min ago'  },
  { id: 'ca_ghi789jkl012', name: 'Mobile App iOS',     created: '5 months ago', type: 'Public',        status: 'Active',   requests: '32,100', lastUsed: '5 min ago'  },
  { id: 'ca_mno345pqr678', name: 'Mobile App Android', created: '5 months ago', type: 'Public',        status: 'Active',   requests: '28,450', lastUsed: '12 min ago' },
  { id: 'ca_stu901vwx234', name: 'Partner API',        created: '8 months ago', type: 'Confidential', status: 'Active',   requests: '15,200', lastUsed: '1 hour ago' },
  { id: 'ca_yza567bcd890', name: 'Legacy System',      created: '1 year ago',   type: 'Confidential', status: 'Inactive', requests: '0',      lastUsed: '3 days ago' },
]

// ── State ──────────────────────────────────────────────────────────────────────
const search       = ref('')
const activeFilter = ref<'All' | ClientStatus>('All')
const filterOpen   = ref(false)

const filteredClients = computed(() => {
  const q = search.value.toLowerCase()
  return clients.filter((c) => {
    const matchesSearch = !q || c.name.toLowerCase().includes(q) || c.id.toLowerCase().includes(q)
    const matchesFilter = activeFilter.value === 'All' || c.status === activeFilter.value
    return matchesSearch && matchesFilter
  })
})

// ── Actions ────────────────────────────────────────────────────────────────────
const copyId = (id: string) => navigator.clipboard.writeText(id).catch(() => {})

// ── Style maps ─────────────────────────────────────────────────────────────────
const typeBadgeClass: Record<ClientType, string> = {
  Confidential: 'bg-blue-50 text-blue-600 ring-1 ring-inset ring-blue-200',
  Public:       'bg-zinc-100 text-zinc-500 ring-1 ring-inset ring-zinc-200',
}

const statusConfig: Record<ClientStatus, { dot: string; pill: string }> = {
  Active:   { dot: 'bg-emerald-500 shadow-[0_0_6px_1px_rgba(16,185,129,0.45)]', pill: 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200' },
  Inactive: { dot: 'bg-zinc-300',  pill: 'bg-zinc-100 text-zinc-400 ring-1 ring-inset ring-zinc-200' },
}

// ── Metric cards data ──────────────────────────────────────────────────────────
const metrics = [
  {
    label: 'Auth Success Rate',
    value: '99.2%',
    sub: '+0.3% vs. yesterday',
    icon: CheckCircle2,
    iconClass: 'text-emerald-600',
    iconBg: 'bg-emerald-50',
    accentBar: 'bg-emerald-500',
    trend: 'up',
  },
  {
    label: 'Avg Latency',
    value: '42ms',
    sub: 'P95 · 98ms',
    icon: Timer,
    iconClass: 'text-blue-600',
    iconBg: 'bg-blue-50',
    accentBar: 'bg-blue-500',
    trend: null,
  },
  {
    label: 'Active Sessions',
    value: '3,241',
    sub: 'Live across all clients',
    icon: Radio,
    iconClass: 'text-violet-600',
    iconBg: 'bg-violet-50',
    accentBar: 'bg-violet-500',
    trend: 'live',
  },
]
</script>

<template>
  <div class="space-y-7 px-1">

    <!-- ── Page Header ── -->
    <div class="flex items-center justify-between pt-1">
      <div>
        <h1 class="text-[1.35rem] font-bold tracking-tight text-foreground">Clients</h1>
        <p class="text-[0.8rem] text-muted-foreground mt-0.5 leading-relaxed">
          Manage your registered OAuth2 applications.
        </p>
      </div>
      <RouterLink to="/admin/clients/new">
        <Button
          variant="default"
          size="sm"
          class="h-8.5 px-3.5 text-[0.8rem] font-semibold gap-1.5 shadow-sm"
        >
          <Plus class="h-3.5 w-3.5" />
          New client
        </Button>
      </RouterLink>
    </div>

    <!-- ── Toolbar ── -->
    <div class="flex items-center gap-2.5">
      <!-- Search -->
      <div class="relative w-68">
        <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" />
        <Input
          v-model="search"
          class="pl-9 h-8.5 text-[0.8rem] bg-muted/40 border-border/60 focus-visible:bg-background placeholder:text-muted-foreground/50 transition-colors"
          placeholder="Search by name or client ID…"
        />
      </div>

      <!-- Filter dropdown -->
      <DropdownMenu v-model:open="filterOpen">
        <DropdownMenuTrigger as-child>
          <Button
            variant="outline"
            size="sm"
            class="h-8.5 gap-2 text-[0.8rem] font-medium border-border/60 transition-colors"
            :class="activeFilter !== 'All'
              ? 'bg-foreground/5 text-foreground border-foreground/20'
              : 'bg-transparent text-muted-foreground hover:text-foreground'"
          >
            <Filter class="h-3.5 w-3.5" />
            {{ activeFilter === 'All' ? 'Filter' : activeFilter }}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" class="w-32 text-[0.8rem]">
          <DropdownMenuItem
            v-for="opt in ['All', 'Active', 'Inactive']"
            :key="opt"
            class="text-[0.8rem] gap-2 cursor-pointer"
            :class="activeFilter === opt ? 'font-semibold text-foreground' : 'text-muted-foreground'"
            @click="activeFilter = opt as typeof activeFilter"
          >
            {{ opt }}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <!-- Divider + count -->
      <div class="ml-auto flex items-center gap-2.5">
        <span class="text-[0.72rem] font-medium text-muted-foreground/60 tabular-nums">
          {{ filteredClients.length }} client{{ filteredClients.length !== 1 ? 's' : '' }}
        </span>
      </div>
    </div>

    <!-- ── Table Card ── -->
    <div class="rounded-xl border border-border/70 bg-card shadow-sm overflow-hidden">
      <table class="w-full border-collapse">
        <!-- Head -->
        <thead>
          <tr class="bg-muted/30 border-b border-border/60">
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Application</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Client ID</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Type</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Status</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Requests Today</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/60 whitespace-nowrap">Last Used</th>
            <th class="px-5 py-2.5 w-10" />
          </tr>
        </thead>

        <!-- Body -->
        <tbody class="divide-y divide-border/40">
          <tr
            v-for="(client, i) in filteredClients"
            :key="client.id"
            class="group hover:bg-muted/20 transition-colors duration-100"
            :class="client.status === 'Inactive' ? 'opacity-45 hover:opacity-60' : ''"
          >
            <!-- Application -->
            <td class="px-5 py-3.5">
              <p class="text-[0.825rem] font-semibold text-foreground leading-tight">{{ client.name }}</p>
              <p class="text-[0.7rem] text-muted-foreground/60 mt-0.5">Created {{ client.created }}</p>
            </td>

            <!-- Client ID -->
            <td class="px-5 py-3.5">
              <div class="flex items-center gap-1.5">
                <code class="font-mono text-[10.5px] text-muted-foreground bg-muted/60 px-1.5 py-0.5 rounded-md border border-border/30 tracking-tight">
                  {{ client.id }}
                </code>
                <button
                  class="opacity-0 group-hover:opacity-100 transition-opacity hover:text-foreground"
                  title="Copy client ID"
                  @click="copyId(client.id)"
                >
                  <Copy class="h-3 w-3 text-muted-foreground/50 hover:text-muted-foreground transition-colors" />
                </button>
              </div>
            </td>

            <!-- Type -->
            <td class="px-5 py-3.5">
              <span
                class="inline-block text-[9.5px] font-bold uppercase tracking-[0.1em] px-2 py-0.5 rounded-md"
                :class="typeBadgeClass[client.type]"
              >
                {{ client.type }}
              </span>
            </td>

            <!-- Status -->
            <td class="px-5 py-3.5">
              <span
                class="inline-flex items-center gap-1.5 text-[0.72rem] font-semibold px-2 py-0.5 rounded-md"
                :class="statusConfig[client.status].pill"
              >
                <span
                  class="h-1.5 w-1.5 rounded-full shrink-0"
                  :class="statusConfig[client.status].dot"
                />
                {{ client.status }}
              </span>
            </td>

            <!-- Requests -->
            <td class="px-5 py-3.5">
              <span
                class="text-[0.825rem] font-bold text-foreground tabular-nums"
              >
                {{ client.requests }}
              </span>
            </td>

            <!-- Last Used -->
            <td class="px-5 py-3.5">
              <span class="text-[0.72rem] text-muted-foreground/60 font-medium">{{ client.lastUsed }}</span>
            </td>

            <!-- Actions -->
            <td class="px-4 py-3.5 text-right">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <Button
                    variant="ghost"
                    size="icon"
                    class="h-7 w-7 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity hover:bg-muted"
                  >
                    <MoreHorizontal class="h-3.5 w-3.5 text-muted-foreground" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" class="w-40 text-[0.8rem]">
                  <DropdownMenuItem class="text-[0.8rem] cursor-pointer">View details</DropdownMenuItem>
                  <DropdownMenuItem class="text-[0.8rem] cursor-pointer">Edit</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem class="text-[0.8rem] text-red-500 focus:text-red-600 focus:bg-red-50 cursor-pointer">
                    Delete
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </td>
          </tr>

          <!-- Empty state -->
          <tr v-if="filteredClients.length === 0">
            <td colspan="7">
              <div class="flex flex-col items-center justify-center py-20 gap-2.5">
                <div class="h-12 w-12 rounded-xl bg-muted/50 flex items-center justify-center border border-border/50">
                  <Database class="h-5 w-5 text-muted-foreground/40" />
                </div>
                <p class="text-[0.8rem] font-semibold text-foreground mt-1">No clients found</p>
                <p class="text-[0.75rem] text-muted-foreground/60">Try adjusting your search or filter.</p>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ── Metric Cards ── -->
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-3.5">
      <div
        v-for="card in metrics"
        :key="card.label"
        class="relative bg-card border border-border/70 rounded-xl p-5 shadow-sm overflow-hidden flex items-start gap-4 group hover:shadow-md transition-shadow duration-200"
      >
        <!-- Top accent bar -->
        <div
          class="absolute top-0 left-5 right-5 h-[2px] rounded-b-full opacity-70"
          :class="card.accentBar"
        />

        <!-- Icon -->
        <div
          class="h-9 w-9 rounded-lg flex items-center justify-center shrink-0 mt-0.5"
          :class="card.iconBg"
        >
          <component :is="card.icon" class="h-4.5 w-4.5" :class="card.iconClass" />
        </div>

        <!-- Text -->
        <div class="min-w-0 flex-1">
          <p class="text-[0.68rem] font-semibold uppercase tracking-[0.09em] text-muted-foreground/60">
            {{ card.label }}
          </p>
          <p class="text-[1.75rem] font-bold text-foreground mt-1 leading-none tabular-nums">
            {{ card.value }}
          </p>
          <p class="text-[0.7rem] text-muted-foreground/60 mt-2 flex items-center gap-1.5 font-medium">
            <span
              v-if="card.trend === 'live'"
              class="inline-block h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse shrink-0"
            />
            <Activity
              v-else-if="card.trend === 'up'"
              class="h-3 w-3 text-emerald-600 shrink-0"
            />
            {{ card.sub }}
          </p>
        </div>
      </div>
    </div>

  </div>
</template>