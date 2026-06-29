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
const isLoading = computed(() => adminStore.dashboardLoading)

// ── Data ────────────────────────────────────────────────────────────────────────
const chartBars = computed(() => data.value.chartBars)
const topClients = computed(() => data.value.topClients)
const recentActivity = computed(() => data.value.recentActivity)
const alerts = computed(() => data.value.alerts)

// ── Stat cards — augmented with icons (icon is UI-only, not from API) ──────────
const statIcons = [LayoutGrid, Users, Activity, CheckCircle, Radio, AlertCircle]
const statIconClasses = [
  'text-primary/70',
  'text-blue-500',
  'text-violet-500',
  'text-green-500',
  'text-emerald-500',
  'text-red-400',
]
const stats = computed(() => data.value.stats.map((s, i) => ({
  ...s,
  icon: statIcons[i],
  iconClass: statIconClasses[i],
})))

// ── Refresh ────────────────────────────────────────────────────────────────────
const isRefreshing = ref(false)
async function refresh() {
  isRefreshing.value = true
  await adminStore.fetchDashboard()
  isRefreshing.value = false
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
  Critical: 'bg-red-500/10 text-red-700 dark:text-red-400',
  High:     'bg-amber-500/10 text-amber-700 dark:text-amber-400',
  Medium:   'bg-blue-500/10 text-blue-700 dark:text-blue-400',
  Low:      'bg-green-500/10 text-green-700 dark:text-green-400',
}
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 space-y-8 font-sans">
    <!-- ── Page Header ── -->
    <header class="flex items-start justify-between animate-fade-up">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight text-foreground">Dashboard</h1>
        <p class="text-sm text-muted-foreground mt-1">Overview of your OAuth2 deployment.</p>
      </div>
      <Button variant="outline" size="icon" :disabled="isRefreshing" @click="refresh" class="mt-1" aria-label="Refresh dashboard data">
        <RotateCw class="h-4 w-4" :class="{ 'animate-spin': isRefreshing }" aria-hidden="true" />
      </Button>
    </header>

    <!-- ── Loading Skeleton ── -->
    <template v-if="isLoading && !adminStore.dashboardData">
      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        <div v-for="n in 6" :key="n" class="bg-card border border-border rounded-xl p-5 space-y-3">
          <div class="skeleton h-3 w-20" />
          <div class="skeleton h-8 w-16" />
          <div class="skeleton h-3 w-24" />
        </div>
      </div>
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div class="lg:col-span-2 bg-card border border-border rounded-xl p-6">
          <div class="skeleton h-52 w-full rounded-lg" />
        </div>
        <div class="bg-card border border-border rounded-xl p-6 space-y-4">
          <div v-for="n in 5" :key="n" class="space-y-2">
            <div class="skeleton h-3 w-full" />
            <div class="skeleton h-1.5 w-full rounded-full" />
          </div>
        </div>
      </div>
    </template>

    <!-- ── Dashboard Content ── -->
    <template v-else>
      <!-- ── Stat Cards ── -->
      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        <div
          v-for="(card, index) in stats"
          :key="card.label"
          class="bg-card border border-border rounded-xl p-5 shadow-sm flex flex-col gap-3 hover-lift animate-fade-up"
          :class="'stagger-' + (index + 1)"
        >
          <div class="flex justify-between items-start">
            <span class="text-[11px] font-semibold text-muted-foreground uppercase tracking-wider leading-tight">
              {{ card.label }}
            </span>
            <div class="h-8 w-8 rounded-lg bg-muted/60 flex items-center justify-center shrink-0">
              <component :is="card.icon" class="h-4 w-4" :class="card.iconClass" aria-hidden="true" />
            </div>
          </div>
          <p class="text-2xl sm:text-3xl font-bold text-foreground tabular-nums">
            {{ card.value }}
          </p>
          <p class="text-xs text-muted-foreground flex items-center gap-1.5">
            <template v-if="card.label === 'Active Sessions'">
              <span class="inline-block h-1.5 w-1.5 rounded-full bg-green-500 animate-pulse shrink-0" aria-hidden="true" />
            </template>
            <span v-if="card.trendUp === true" class="text-green-600 dark:text-green-400 font-semibold">{{ card.trend }}</span>
            <span v-else-if="card.trendUp === false" class="text-red-500 dark:text-red-400 font-semibold">{{ card.trend }}</span>
            <span v-else>{{ card.trend }}</span>
          </p>
        </div>
      </div>

      <!-- ── Two-column: Chart + Top Clients ── -->
      <section aria-label="OAuth request charts and top clients" class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <!-- OAuth Requests chart -->
        <div class="lg:col-span-2 bg-card border border-border rounded-xl p-6 shadow-sm animate-fade-up stagger-2">
          <div class="flex justify-between items-center mb-5">
            <div>
              <h2 class="text-base font-semibold text-foreground">OAuth Requests</h2>
              <p class="text-xs text-muted-foreground mt-0.5">Authorization & token activity</p>
            </div>
            <span class="text-xs font-medium text-muted-foreground bg-muted px-3 py-1 rounded-full">Last 24h</span>
          </div>

          <!-- Bar chart -->
          <div
            class="h-52 bg-muted/20 rounded-lg border border-border/50 flex items-end gap-1.5 px-4 pb-4 pt-3"
            role="img"
            aria-label="Bar chart showing OAuth requests over the last 24 hours"
          >
            <div
              v-for="(height, i) in chartBars"
              :key="i"
              class="flex-1 rounded-t-sm bg-primary/30 hover:bg-primary/50 min-h-1 transition-all duration-300 cursor-pointer"
              :style="{ height: height + '%' }"
            />
          </div>

          <!-- X-axis -->
          <div class="flex justify-between text-[11px] text-muted-foreground mt-2 px-1 font-medium" aria-hidden="true">
            <span>00:00</span>
            <span>06:00</span>
            <span>12:00</span>
            <span>18:00</span>
            <span>23:00</span>
          </div>

          <!-- Legend -->
          <div class="flex gap-5 mt-4 pt-3 border-t border-border/50">
            <span class="flex items-center gap-2 text-xs text-muted-foreground">
              <span class="h-2.5 w-2.5 rounded-full bg-primary/60 inline-block shrink-0" />
              Authorizations
            </span>
            <span class="flex items-center gap-2 text-xs text-muted-foreground">
              <span class="h-2.5 w-2.5 rounded-full bg-muted-foreground/50 inline-block shrink-0" />
              Tokens
            </span>
          </div>
        </div>

        <!-- Top Clients -->
        <div class="bg-card border border-border rounded-xl p-6 shadow-sm animate-fade-up stagger-3">
          <div class="flex justify-between items-center mb-5">
            <div>
              <h2 class="text-base font-semibold text-foreground">Top Clients</h2>
              <p class="text-xs text-muted-foreground mt-0.5">By usage today</p>
            </div>
            <span class="text-xs font-medium bg-muted text-muted-foreground px-3 py-1 rounded-full">Today</span>
          </div>

          <div class="space-y-4">
            <div v-for="client in topClients" :key="client.name">
              <div class="flex justify-between items-center mb-1.5">
                <span class="text-sm font-medium text-foreground truncate">{{ client.name }}</span>
                <span class="text-xs text-muted-foreground shrink-0 ml-2 tabular-nums">{{ client.count }}</span>
              </div>
              <div class="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                <div
                  class="h-full bg-primary/60 rounded-full transition-all duration-500"
                  :style="{ width: client.pct + '%' }"
                />
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ── Bottom two-column: Activity + Alerts ── -->
      <section aria-label="Recent activity and security alerts" class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- Recent Activity -->
        <div class="bg-card border border-border rounded-xl p-6 shadow-sm animate-fade-up stagger-3">
          <div class="flex justify-between items-center mb-4">
            <div>
              <h2 class="text-base font-semibold text-foreground">Recent Activity</h2>
              <p class="text-xs text-muted-foreground mt-0.5">Latest events across all clients</p>
            </div>
            <button class="text-xs font-medium text-primary hover:text-primary/80 transition-colors focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2 rounded-sm">
              View all
            </button>
          </div>

          <div class="divide-y divide-border/50">
            <div
              v-for="(item, i) in recentActivity"
              :key="i"
              class="flex items-start gap-3 py-3 first:pt-0 last:pb-0"
            >
              <span
                class="h-2 w-2 rounded-full mt-2 shrink-0"
                :class="dotClass[item.type]"
                aria-hidden="true"
              />
              <div class="flex-1 min-w-0">
                <p class="text-sm font-medium text-foreground leading-snug">{{ item.desc }}</p>
                <p class="text-xs text-muted-foreground mt-0.5">{{ item.client }}</p>
              </div>
              <span class="text-[11px] text-muted-foreground shrink-0 mt-0.5 tabular-nums">{{ item.time }}</span>
            </div>
          </div>
        </div>

        <!-- Security Alerts -->
        <div class="bg-card border border-border rounded-xl p-6 shadow-sm animate-fade-up stagger-4">
          <div class="flex justify-between items-center mb-4">
            <div>
              <h2 class="text-base font-semibold text-foreground">Security Alerts</h2>
              <p class="text-xs text-muted-foreground mt-0.5">Active threats and anomalies</p>
            </div>
            <RouterLink
              to="/admin/security"
              class="text-xs font-medium text-primary hover:text-primary/80 transition-colors focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-2 rounded-sm"
            >
              View all
            </RouterLink>
          </div>

          <div class="space-y-3">
            <div
              v-for="alert in alerts"
              :key="alert.title"
              class="flex items-start gap-3 p-4 rounded-lg bg-muted/30 border border-border/40 transition-colors hover:bg-muted/50"
            >
              <!-- Accent bar -->
              <div
                class="w-1 self-stretch rounded-full shrink-0"
                :class="accentClass[alert.severity]"
                aria-hidden="true"
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
                <p class="text-[11px] text-muted-foreground mt-1.5 tabular-nums">{{ alert.time }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tabular-nums {
  font-variant-numeric: tabular-nums;
}

@media (prefers-reduced-motion: reduce) {
  .animate-fade-up,
  .animate-spin,
  .animate-pulse {
    animation: none !important;
  }
}
</style>
