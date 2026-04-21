<script setup lang="ts">
import { computed, ref } from 'vue'
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

// ── Types ──────────────────────────────────────────────────────────────────────
type Severity = 'Critical' | 'High' | 'Medium' | 'Low'

interface SecurityEvent {
  severity: Severity
  title: string
  description: string
  ip: string
  location: string
  time: string
  resolved: boolean
}

// ── State ──────────────────────────────────────────────────────────────────────
const activeTab = ref<'Security Events' | 'Audit Logs' | 'IP Access'>('Security Events')
const search    = ref('')

const tabs = ['Security Events', 'Audit Logs', 'IP Access'] as const

// ── Static events ──────────────────────────────────────────────────────────────
const events: SecurityEvent[] = [
  {
    severity: 'Critical', title: 'Brute Force Attack Detected',
    description: '500+ failed login attempts in 5 minutes.',
    ip: '192.168.1.105', location: 'Unknown VPN',  time: '2 min ago',   resolved: false,
  },
  {
    severity: 'High', title: 'Unusual Token Pattern',
    description: "Token refresh rate 10x normal for 'mobile-app'.",
    ip: '45.33.32.156', location: 'New York, US',  time: '15 min ago',  resolved: false,
  },
  {
    severity: 'Medium', title: 'New Admin Login Location',
    description: 'Admin signed in from new geographic location.',
    ip: '89.247.1.42',  location: 'Berlin, Germany', time: '1 hour ago', resolved: true,
  },
  {
    severity: 'Low', title: 'Mass Token Revocation',
    description: "All tokens for 'legacy-system' were revoked.",
    ip: '10.0.0.1',     location: 'Internal',      time: '3 hours ago', resolved: true,
  },
  {
    severity: 'High', title: 'SQL Injection Attempt',
    description: 'Malicious payload detected in authorization request.',
    ip: '203.0.113.42', location: 'Unknown',       time: '5 hours ago', resolved: true,
  },
]

const filteredEvents = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return events
  return events.filter(
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

// ── Stat cards ─────────────────────────────────────────────────────────────────
const stats = [
  {
    label: 'Critical Alerts',  value: '3',     icon: AlertCircle, iconClass: 'text-red-400',
    cardClass: 'bg-red-50 border-red-100',
  },
  {
    label: 'Blocked Attacks',  value: '156',   icon: ShieldAlert, iconClass: 'text-amber-400',
    cardClass: 'bg-card border-border/70',
  },
  {
    label: 'Tokens Revoked',   value: '42',    icon: CheckCircle, iconClass: 'text-green-400',
    cardClass: 'bg-card border-border/70',
  },
  {
    label: 'Audit Events',     value: '1,247', icon: FileText,    iconClass: 'text-muted-foreground',
    cardClass: 'bg-card border-border/70',
  },
]
</script>

<template>
  <div class="space-y-6">
    <!-- ── Header ── -->
    <div>
      <h1 class="text-2xl font-bold text-foreground tracking-tight">Security</h1>
      <p class="text-sm text-muted-foreground mt-1">
        Monitor security events, access patterns, and audit logs.
      </p>
    </div>

    <!-- ── Stat Cards ── -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <div
        v-for="card in stats"
        :key="card.label"
        class="border rounded-xl p-4 shadow-sm"
        :class="card.cardClass"
      >
        <div class="flex justify-between items-start">
          <span class="text-xs font-medium text-muted-foreground uppercase tracking-wide leading-tight">
            {{ card.label }}
          </span>
          <div class="h-7 w-7 rounded-md bg-white/60 flex items-center justify-center shrink-0">
            <component :is="card.icon" class="h-3.5 w-3.5" :class="card.iconClass" />
          </div>
        </div>
        <p class="text-2xl font-bold text-foreground mt-2" style="font-variant-numeric: tabular-nums">
          {{ card.value }}
        </p>
      </div>
    </div>

    <!-- ── Tab Bar ── -->
    <div class="flex border-b border-border">
      <button
        v-for="tab in tabs"
        :key="tab"
        class="px-1 py-2.5 mr-6 text-sm border-b-2 transition-colors whitespace-nowrap"
        :class="activeTab === tab
          ? 'border-foreground text-foreground font-medium'
          : 'border-transparent text-muted-foreground hover:text-foreground'"
        @click="activeTab = tab"
      >
        {{ tab }}
      </button>
    </div>

    <!-- ── Security Events tab ── -->
    <template v-if="activeTab === 'Security Events'">
      <!-- Toolbar -->
      <div class="flex items-center gap-3">
        <div class="relative w-72">
          <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" />
          <Input v-model="search" class="pl-9 h-9 text-sm" placeholder="Search events…" />
        </div>
        <Button variant="outline" size="sm" class="h-9 font-medium gap-0">
          <Filter class="h-4 w-4 mr-1.5" />
          All severities
          <ChevronDown class="h-4 w-4 ml-1.5" />
        </Button>
        <span class="text-xs text-muted-foreground ml-auto">
          {{ filteredEvents.length }} event{{ filteredEvents.length !== 1 ? 's' : '' }}
        </span>
      </div>

      <!-- Event list -->
      <div class="bg-card border border-border rounded-lg shadow-sm overflow-hidden">
        <div
          v-for="(event, i) in filteredEvents"
          :key="i"
          class="flex items-start gap-3 p-4 border-t border-border/50 hover:bg-muted/30 transition-colors group"
          :class="[{ 'border-t-0': i === 0 }, event.resolved ? 'opacity-70' : '']"
        >
          <!-- Accent bar -->
          <div
            class="w-0.5 rounded-full self-stretch shrink-0 mt-0.5 min-h-[40px]"
            :class="accentBar[event.severity]"
          />

          <!-- Content -->
          <div class="flex-1 min-w-0">
            <!-- Row 1: badges + title -->
            <div class="flex items-center gap-2 flex-wrap">
              <span
                class="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-sm shrink-0"
                :class="severityBadge[event.severity]"
              >
                {{ event.severity }}
              </span>
              <span class="text-sm font-medium text-foreground">{{ event.title }}</span>
              <span
                v-if="event.resolved"
                class="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-sm bg-green-50 text-green-700 shrink-0"
              >
                Resolved
              </span>
            </div>

            <!-- Row 2: description -->
            <p class="text-sm text-muted-foreground mt-1">{{ event.description }}</p>

            <!-- Row 3: metadata -->
            <div class="flex items-center gap-3 mt-2 text-xs text-muted-foreground flex-wrap">
              <span class="font-mono">{{ event.ip }}</span>
              <span>·</span>
              <span>{{ event.location }}</span>
              <span class="ml-auto shrink-0">{{ event.time }}</span>
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

        <!-- Empty search state -->
        <div v-if="filteredEvents.length === 0" class="text-center py-12">
          <Shield class="h-8 w-8 text-muted-foreground/40 mx-auto mb-3" />
          <p class="text-sm font-medium text-foreground">No events found</p>
          <p class="text-xs text-muted-foreground mt-1">Try adjusting your search.</p>
        </div>
      </div>
    </template>

    <!-- ── Audit Logs tab ── -->
    <div v-else-if="activeTab === 'Audit Logs'" class="text-center py-16">
      <div class="h-12 w-12 rounded-xl bg-muted/50 border border-border/50 flex items-center justify-center mx-auto mb-3">
        <ClipboardList class="h-5 w-5 text-muted-foreground/40" />
      </div>
      <p class="text-sm font-medium text-foreground">Coming soon</p>
      <p class="text-xs text-muted-foreground mt-1">This section is under development.</p>
    </div>

    <!-- ── IP Access tab ── -->
    <div v-else-if="activeTab === 'IP Access'" class="text-center py-16">
      <div class="h-12 w-12 rounded-xl bg-muted/50 border border-border/50 flex items-center justify-center mx-auto mb-3">
        <Globe class="h-5 w-5 text-muted-foreground/40" />
      </div>
      <p class="text-sm font-medium text-foreground">Coming soon</p>
      <p class="text-xs text-muted-foreground mt-1">This section is under development.</p>
    </div>
  </div>
</template>
