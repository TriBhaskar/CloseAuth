<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertCircle,
  CheckCircle,
  ChevronDown,
  ClipboardList,
  Eye,
  FileText,
  Filter,
  Globe,
  MoreHorizontal,
  Search,
  Shield,
  ShieldAlert,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { securityMock } from '@/api/mocks/securityMocks'
import { useAdminStore } from '@/stores/admin'
import type { Severity } from '@/api/models'

const adminStore = useAdminStore()

onMounted(async () => {
  await adminStore.fetchSecurity()
})

const data = computed(() => adminStore.securityData ?? securityMock)
const events = computed(() => data.value.events)

// ── State ──────────────────────────────────────────────────────────────────────
const activeTab = ref<'Security Events' | 'Audit Logs' | 'IP Access'>('Security Events')
const search    = ref('')
const tabs      = ['Security Events', 'Audit Logs', 'IP Access'] as const

const filteredEvents = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return events.value
  return events.value.filter(
    (e) => e.title.toLowerCase().includes(q) || e.description.toLowerCase().includes(q),
  )
})

// ── Style maps ─────────────────────────────────────────────────────────────────
const accentBar: Record<Severity, string> = {
  Critical: 'bg-red-500',
  High:     'bg-amber-500',
  Medium:   'bg-blue-400',
  Low:      'bg-border',
}

const severityBadge: Record<Severity, string> = {
  Critical: 'bg-red-50 text-red-700',
  High:     'bg-amber-50 text-amber-700',
  Medium:   'bg-blue-50 text-blue-700',
  Low:      'bg-muted text-muted-foreground',
}

// ── Stat cards — augmented with icons (UI-only) ───────────────────────────────
const statIconMap = [AlertCircle, ShieldAlert, CheckCircle, FileText]
const statCardClass = [
  'bg-red-50 border-red-100',
  'bg-card border-border/70',
  'bg-card border-border/70',
  'bg-card border-border/70',
]
const statIconClass = ['text-red-400', 'text-amber-400', 'text-green-400', 'text-muted-foreground']
const stats = computed(() => data.value.stats.map((s, i) => ({
  ...s,
  icon:      statIconMap[i],
  iconClass: statIconClass[i],
  cardClass: statCardClass[i],
})))
</script>

<template>
  <div class="p-6 space-y-10 font-sans">
    <!-- ── Header ── -->
    <div>
      <h1 class="text-2xl font-bold text-foreground tracking-tight">Security</h1>
      <p class="text-sm font-medium text-muted-foreground mt-1">
        Monitor security events, access patterns, and audit logs.
      </p>
    </div>

    <!-- ── Stat Cards ── -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 pt-5">
      <div
        v-for="card in stats"
        :key="card.label"
        class="border rounded-xl p-5 shadow-sm flex flex-col gap-3"
        :class="card.cardClass"
      >
        <div class="flex justify-between items-start">
          <span class="text-xs font-semibold text-muted-foreground uppercase tracking-widest leading-tight">
            {{ card.label }}
          </span>
          <div class="h-8 w-8 rounded-lg bg-white/60 flex items-center justify-center shrink-0">
            <component :is="card.icon" class="h-4 w-4" :class="card.iconClass" />
          </div>
        </div>
        <p class="text-3xl font-bold text-foreground" style="font-variant-numeric: tabular-nums">
          {{ card.value }}
        </p>
      </div>
    </div>

    <!-- ── Tab Bar ── -->
    <div class="flex border-b border-border pt-5">
      <button
        v-for="tab in tabs"
        :key="tab"
        class="px-1 py-3 mr-6 text-sm font-medium border-b-2 transition-colors whitespace-nowrap"
        :class="activeTab === tab
          ? 'border-foreground text-foreground'
          : 'border-transparent text-muted-foreground hover:text-foreground'"
        @click="activeTab = tab"
      >
        {{ tab }}
      </button>
    </div>

    <!-- ── Security Events tab ── -->
    <template v-if="activeTab === 'Security Events'">
      <!-- Toolbar -->
      <div class="flex items-center gap-3 pt-3 pb-3">
        <div class="relative w-72">
          <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" />
          <Input v-model="search" class="pl-9 h-9 text-sm" placeholder="Search events…" />
        </div>
        <Button variant="outline" size="sm" class="h-9 font-medium gap-0">
          <Filter class="h-4 w-4 mr-1.5" />
          All severities
          <ChevronDown class="h-4 w-4 ml-1.5" />
        </Button>
        <span class="text-xs font-medium text-muted-foreground ml-auto tabular-nums">
          {{ filteredEvents.length }} event{{ filteredEvents.length !== 1 ? 's' : '' }}
        </span>
      </div>

      <!-- Event list -->
      <div class="bg-card border border-border rounded-xl shadow-sm overflow-hidden">
        <div
          v-for="(event, i) in filteredEvents"
          :key="i"
          class="flex items-start gap-3 p-5 hover:bg-muted/30 transition-colors group"
          :class="[{ 'border-t border-border/50': i > 0 }, event.resolved ? 'opacity-70' : '']"
        >
          <!-- Accent bar -->
          <div class="w-1 rounded-full self-stretch shrink-0 min-h-[40px]" :class="accentBar[event.severity]" />

          <!-- Content -->
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 flex-wrap mb-1">
              <span
                class="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md shrink-0"
                :class="severityBadge[event.severity]"
              >
                {{ event.severity }}
              </span>
              <span class="text-sm font-semibold text-foreground">{{ event.title }}</span>
              <span
                v-if="event.resolved"
                class="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md bg-green-50 text-green-700 shrink-0"
              >
                Resolved
              </span>
            </div>

            <p class="text-sm font-medium text-muted-foreground">{{ event.description }}</p>

            <div class="flex items-center gap-3 mt-2 text-xs font-medium text-muted-foreground/70 flex-wrap">
              <span class="font-mono">{{ event.ip }}</span>
              <span>·</span>
              <span>{{ event.location }}</span>
              <span class="ml-auto shrink-0 tabular-nums">{{ event.timestamp }}</span>
            </div>
          </div>

          <!-- Hover actions -->
          <div class="flex gap-1 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
            <Button variant="ghost" size="icon" class="h-7 w-7 rounded-md">
              <Eye class="h-3.5 w-3.5 text-muted-foreground" />
            </Button>
            <Button variant="ghost" size="icon" class="h-7 w-7 rounded-md">
              <MoreHorizontal class="h-3.5 w-3.5 text-muted-foreground" />
            </Button>
          </div>
        </div>

        <div v-if="filteredEvents.length === 0" class="text-center py-16">
          <Shield class="h-8 w-8 text-muted-foreground/40 mx-auto mb-3" />
          <p class="text-sm font-semibold text-foreground">No events found</p>
          <p class="text-xs text-muted-foreground mt-1">Try adjusting your search.</p>
        </div>
      </div>
    </template>

    <!-- ── Audit Logs tab ── -->
    <div v-else-if="activeTab === 'Audit Logs'" class="bg-card border border-border rounded-xl shadow-sm text-center py-20">
      <div class="h-12 w-12 rounded-xl bg-muted/50 border border-border/50 flex items-center justify-center mx-auto mb-3">
        <ClipboardList class="h-5 w-5 text-muted-foreground/40" />
      </div>
      <p class="text-sm font-semibold text-foreground">Coming soon</p>
      <p class="text-xs text-muted-foreground mt-1">This section is under development.</p>
    </div>

    <!-- ── IP Access tab ── -->
    <div v-else-if="activeTab === 'IP Access'" class="bg-card border border-border rounded-xl shadow-sm text-center py-20">
      <div class="h-12 w-12 rounded-xl bg-muted/50 border border-border/50 flex items-center justify-center mx-auto mb-3">
        <Globe class="h-5 w-5 text-muted-foreground/40" />
      </div>
      <p class="text-sm font-semibold text-foreground">Coming soon</p>
      <p class="text-xs text-muted-foreground mt-1">This section is under development.</p>
    </div>
  </div>
</template>
