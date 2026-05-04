<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Activity,
  AlertCircle,
  CheckCircle,
  LayoutGrid,
  Radio,
  RotateCw,
  Users,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { dashboardMock } from '@/api/mocks/dashboardMocks'
import { useAdminStore } from '@/stores/admin'
import type { ActivityType, Severity } from '@/api/models'

const adminStore = useAdminStore()

// Fetch real data on mount — fall back to mock if API not ready
onMounted(async () => {
  await adminStore.fetchDashboard()
})

// Use API data if available, else mock
const data = computed(() => adminStore.dashboardData ?? dashboardMock)

// ── Data ────────────────────────────────────────────────────────────────────────
const chartBars = computed(() => data.value.chartBars)
const topClients = computed(() => data.value.topClients)
const recentActivity = computed(() => data.value.recentActivity)
const alerts = computed(() => data.value.alerts)

// ── Stat cards — augmented with icons (icon is UI-only, not from API) ──────────
const statIcons = [LayoutGrid, Users, Activity, CheckCircle, Radio, AlertCircle]
const statIconClasses = [
  'text-muted-foreground',
  'text-muted-foreground',
  'text-muted-foreground',
  'text-muted-foreground',
  'text-muted-foreground',
  'text-red-400',
]
const stats = computed(() => data.value.stats.map((s, i) => ({
  ...s,
  icon: statIcons[i],
  iconClass: statIconClasses[i],
})))

// ── Refresh ────────────────────────────────────────────────────────────────────
const isRefreshing = ref(false)
const refresh = () => {
  isRefreshing.value = true
  setTimeout(() => { isRefreshing.value = false }, 1000)
}

// ── Style maps ─────────────────────────────────────────────────────────────────
const dotClass: Record<ActivityType, string> = {
  error:   'bg-red-500',
  success: 'bg-green-500',
  warning: 'bg-amber-500',
  info:    'bg-blue-500',
}

const accentClass: Record<Severity, string> = {
  Critical: 'bg-red-500',
  High:     'bg-amber-500',
  Medium:   'bg-blue-400',
  Low:      'bg-green-400',
}
const badgeClass: Record<Severity, string> = {
  Critical: 'bg-red-50 text-red-700',
  High:     'bg-amber-50 text-amber-700',
  Medium:   'bg-blue-50 text-blue-700',
  Low:      'bg-green-50 text-green-700',
}
</script>

<template>
  <div class="p-6 space-y-10 font-sans">
    <!-- ── Page Header ── -->
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-2xl font-bold tracking-tight text-foreground">Dashboard</h1>
        <p class="text-sm font-medium text-muted-foreground mt-1">Overview of your OAuth2 deployment.</p>
      </div>
      <Button variant="outline" size="icon" @click="refresh" class="mt-1">
        <RotateCw class="h-4 w-4" :class="{ 'animate-spin': isRefreshing }" />
      </Button>
    </div>

    <!-- ── Stat Cards ── -->
    <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 pt-5">
      <div
        v-for="(card, index) in stats"
        :key="card.label"
        class="bg-card border border-border rounded-xl p-5 shadow-sm flex flex-col gap-3 card-elevated hover-lift animate-fade-up"
        :class="'stagger-' + (index + 1)"
      >
        <div class="flex justify-between items-start">
          <span class="text-xs font-semibold text-muted-foreground uppercase tracking-widest leading-tight">
            {{ card.label }}
          </span>
          <div class="h-8 w-8 rounded-lg bg-muted flex items-center justify-center shrink-0">
            <component :is="card.icon" class="h-4 w-4" :class="card.iconClass" />
          </div>
        </div>
        <p class="text-3xl font-bold text-foreground" style="font-variant-numeric: tabular-nums">
          {{ card.value }}
        </p>
        <p class="text-xs font-medium text-muted-foreground flex items-center gap-1">
          <template v-if="card.label === 'Active Sessions'">
            <span class="inline-block h-1.5 w-1.5 rounded-full bg-green-500 animate-pulse shrink-0" />
          </template>
          <span v-if="card.trendUp === true" class="text-green-600 font-semibold">{{ card.trend }}</span>
          <span v-else-if="card.trendUp === false" class="text-red-500 font-semibold">{{ card.trend }}</span>
          <span v-else>{{ card.trend }}</span>
        </p>
      </div>
    </div>

    <!-- ── Two-column: Chart + Top Clients ── -->
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6 pt-5">
      <!-- OAuth Requests chart -->
      <div class="lg:col-span-2 bg-card border border-border rounded-xl p-6 shadow-sm card-elevated animate-fade-up stagger-2">
        <div class="flex justify-between items-center mb-5">
          <div>
            <h2 class="text-base font-bold text-foreground">OAuth Requests</h2>
            <p class="text-xs text-muted-foreground mt-0.5">Authorization & token activity</p>
          </div>
          <span class="text-xs font-medium text-muted-foreground bg-muted px-3 py-1 rounded-full">Last 24h</span>
        </div>

        <!-- Bar chart -->
        <div class="h-52 bg-muted/20 rounded-lg border border-border/50 flex items-end gap-1.5 px-4 pb-4 pt-3">
          <div
            v-for="(height, i) in chartBars"
            :key="i"
            class="flex-1 rounded-t-sm bg-foreground/25 hover:bg-foreground/40 min-h-1 transition-all duration-300 cursor-pointer"
            :style="{ height: height + '%' }"
          />
        </div>

        <!-- X-axis -->
        <div class="flex justify-between text-xs text-muted-foreground mt-2 px-1 font-medium">
          <span>00:00</span>
          <span>06:00</span>
          <span>12:00</span>
          <span>18:00</span>
          <span>23:00</span>
        </div>

        <!-- Legend -->
        <div class="flex gap-5 mt-4 pt-3 border-t border-border/50">
          <span class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
            <span class="h-2.5 w-2.5 rounded-full bg-foreground inline-block shrink-0" />
            Authorizations
          </span>
          <span class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
            <span class="h-2.5 w-2.5 rounded-full bg-muted-foreground inline-block shrink-0" />
            Tokens
          </span>
        </div>
      </div>

      <!-- Top Clients -->
      <div class="bg-card border border-border rounded-xl p-6 shadow-sm card-elevated animate-fade-up stagger-3">
        <div class="flex justify-between items-center mb-5">
          <div>
            <h2 class="text-base font-bold text-foreground">Top Clients</h2>
            <p class="text-xs text-muted-foreground mt-0.5">By usage today</p>
          </div>
          <span class="text-xs font-medium bg-muted text-muted-foreground px-3 py-1 rounded-full">Today</span>
        </div>

        <div class="space-y-4">
          <div v-for="client in topClients" :key="client.name">
            <div class="flex justify-between items-center mb-1.5">
              <span class="text-sm font-semibold text-foreground truncate">{{ client.name }}</span>
              <span class="text-xs font-medium text-muted-foreground shrink-0 ml-2 tabular-nums">{{ client.count }}</span>
            </div>
            <div class="h-1.5 w-full bg-muted rounded-full overflow-hidden">
              <div
                class="h-full bg-foreground/70 rounded-full transition-all duration-500"
                :style="{ width: client.pct + '%' }"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Bottom two-column: Activity + Alerts ── -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 pt-5">
      <!-- Recent Activity -->
      <div class="bg-card border border-border rounded-xl p-6 shadow-sm card-elevated animate-fade-up stagger-3">
        <div class="flex justify-between items-center mb-4">
          <div>
            <h2 class="text-base font-bold text-foreground">Recent Activity</h2>
            <p class="text-xs text-muted-foreground mt-0.5">Latest events across all clients</p>
          </div>
          <button class="text-xs font-semibold text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors">
            View all
          </button>
        </div>

        <div class="divide-y divide-border/50">
          <div
            v-for="(item, i) in recentActivity"
            :key="i"
            class="flex items-start gap-3 py-3"
          >
            <span
              class="h-2 w-2 rounded-full mt-2 shrink-0"
              :class="dotClass[item.type]"
            />
            <div class="flex-1 min-w-0">
              <p class="text-sm font-medium text-foreground leading-snug">{{ item.desc }}</p>
              <p class="text-xs text-muted-foreground mt-0.5">{{ item.client }}</p>
            </div>
            <span class="text-xs font-medium text-muted-foreground shrink-0 mt-0.5 tabular-nums">{{ item.time }}</span>
          </div>
        </div>
      </div>

      <!-- Security Alerts -->
      <div class="bg-card border border-border rounded-xl p-6 shadow-sm card-elevated animate-fade-up stagger-4">
        <div class="flex justify-between items-center mb-4">
          <div>
            <h2 class="text-base font-bold text-foreground">Security Alerts</h2>
            <p class="text-xs text-muted-foreground mt-0.5">Active threats and anomalies</p>
          </div>
          <RouterLink
            to="/admin/security"
            class="text-xs font-semibold text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors"
          >
            View all
          </RouterLink>
        </div>

        <div class="space-y-3">
          <div
            v-for="alert in alerts"
            :key="alert.title"
            class="flex items-start gap-3 p-4 rounded-lg bg-muted/30 border border-border/40"
          >
            <!-- Accent bar -->
            <div
              class="w-1 self-stretch rounded-full shrink-0"
              :class="accentClass[alert.severity]"
            />
            <!-- Content -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1 flex-wrap">
                <span
                  class="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md"
                  :class="badgeClass[alert.severity]"
                >
                  {{ alert.severity }}
                </span>
                <span class="text-sm font-semibold text-foreground">{{ alert.title }}</span>
              </div>
              <p class="text-xs text-muted-foreground leading-relaxed">{{ alert.desc }}</p>
              <p class="text-xs font-medium text-muted-foreground mt-1.5">{{ alert.time }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
