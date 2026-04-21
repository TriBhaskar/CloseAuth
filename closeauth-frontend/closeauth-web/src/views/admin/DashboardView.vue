<script setup lang="ts">
import { ref } from 'vue'
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

// ── Refresh ────────────────────────────────────────────────────────────────────
const isRefreshing = ref(false)
const refresh = () => {
  isRefreshing.value = true
  setTimeout(() => { isRefreshing.value = false }, 1000)
}

// ── Stat cards ─────────────────────────────────────────────────────────────────
const stats = [
  { label: 'OAuth Clients',   value: '24',     trend: '+2 this week',       icon: LayoutGrid,   trendUp: true,  iconClass: 'text-muted-foreground' },
  { label: 'Total Users',     value: '12,847', trend: '+324 this month',    icon: Users,        trendUp: true,  iconClass: 'text-muted-foreground' },
  { label: 'Daily Requests',  value: '89.2K',  trend: '+12.5%',             icon: Activity,     trendUp: true,  iconClass: 'text-muted-foreground' },
  { label: 'Success Rate',    value: '99.2%',  trend: '+0.3%',              icon: CheckCircle,  trendUp: true,  iconClass: 'text-muted-foreground' },
  { label: 'Active Sessions', value: '3,241',  trend: 'Live',               icon: Radio,        trendUp: null,  iconClass: 'text-muted-foreground' },
  { label: 'Failed Auth',     value: '156',    trend: '−23% vs yesterday',  icon: AlertCircle,  trendUp: false, iconClass: 'text-red-400' },
]

// ── Chart bars (24 fixed heights %) ────────────────────────────────────────────
const chartBars = [
  22, 35, 28, 40, 33, 55, 30, 42, 38, 60, 45, 75,
  50, 68, 55, 80, 62, 70, 58, 85, 65, 72, 48, 38,
]

// ── Top clients ────────────────────────────────────────────────────────────────
const topClients = [
  { name: 'Web Application',    count: '45,820', pct: 92 },
  { name: 'Mobile App iOS',     count: '32,100', pct: 64 },
  { name: 'Mobile App Android', count: '28,450', pct: 57 },
  { name: 'Partner API',        count: '15,200', pct: 30 },
  { name: 'Internal Dashboard', count: '7,800',  pct: 16 },
]

// ── Recent activity ────────────────────────────────────────────────────────────
type ActivityType = 'error' | 'success' | 'warning' | 'info'
const dotClass: Record<ActivityType, string> = {
  error:   'bg-red-500',
  success: 'bg-green-500',
  warning: 'bg-amber-500',
  info:    'bg-blue-500',
}
const recentActivity: { type: ActivityType; desc: string; client: string; time: string }[] = [
  { type: 'error',   desc: 'Failed login attempt',       client: 'Mobile App iOS',  time: '2m ago'  },
  { type: 'success', desc: 'New client registered',      client: 'Admin Portal',    time: '15m ago' },
  { type: 'warning', desc: 'Token refresh rate spike',   client: 'Partner API',     time: '1h ago'  },
  { type: 'success', desc: 'OTP verified successfully',  client: 'Web Application', time: '2h ago'  },
  { type: 'info',    desc: 'New admin login location',   client: 'Admin Portal',    time: '3h ago'  },
  { type: 'error',   desc: 'Invalid redirect URI',       client: 'Legacy System',   time: '5h ago'  },
]

// ── Security alerts ────────────────────────────────────────────────────────────
type Severity = 'Critical' | 'High' | 'Medium'
const accentClass: Record<Severity, string> = {
  Critical: 'bg-red-500',
  High:     'bg-amber-500',
  Medium:   'bg-blue-400',
}
const badgeClass: Record<Severity, string> = {
  Critical: 'bg-red-50 text-red-700',
  High:     'bg-amber-50 text-amber-700',
  Medium:   'bg-blue-50 text-blue-700',
}
const alerts: { severity: Severity; title: string; desc: string; time: string }[] = [
  { severity: 'Critical', title: 'Brute Force Attack',    desc: '500+ failed logins in 5min',  time: '2m ago'  },
  { severity: 'High',     title: 'Unusual Token Pattern', desc: 'Refresh rate 10x normal',     time: '15m ago' },
  { severity: 'Medium',   title: 'New Login Location',    desc: 'Admin login from Berlin',      time: '1h ago'  },
]
</script>

<template>
  <div>
    <!-- ── Page Header ── -->
    <div class="flex items-start justify-between mb-8">
      <div>
        <h1 class="text-xl font-semibold text-foreground">Dashboard</h1>
        <p class="text-sm text-muted-foreground mt-1">Overview of your OAuth2 deployment.</p>
      </div>
      <Button variant="ghost" size="icon" @click="refresh">
        <RotateCw class="h-4 w-4" :class="{ 'animate-spin': isRefreshing }" />
      </Button>
    </div>

    <!-- ── Stat Cards ── -->
    <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-6">
      <div
        v-for="card in stats"
        :key="card.label"
        class="bg-card border border-border rounded-lg p-4 shadow-sm"
      >
        <div class="flex justify-between items-start">
          <span class="text-xs font-medium text-muted-foreground uppercase tracking-wide leading-tight">
            {{ card.label }}
          </span>
          <div class="h-7 w-7 rounded-md bg-muted flex items-center justify-center shrink-0">
            <component :is="card.icon" class="h-3.5 w-3.5" :class="card.iconClass" />
          </div>
        </div>
        <p class="text-2xl font-semibold text-foreground mt-2" style="font-variant-numeric: tabular-nums">
          {{ card.value }}
        </p>
        <p class="text-xs text-muted-foreground mt-1">
          <template v-if="card.label === 'Active Sessions'">
            <span class="inline-block h-1.5 w-1.5 rounded-full bg-green-500 animate-pulse mr-1 align-middle" />
          </template>
          <span
            v-if="card.trendUp === true"
            class="text-green-600"
          >{{ card.trend }}</span>
          <span
            v-else-if="card.trendUp === false"
            class="text-red-500"
          >{{ card.trend }}</span>
          <span v-else>{{ card.trend }}</span>
        </p>
      </div>
    </div>

    <!-- ── Two-column: Chart + Top Clients ── -->
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-4 mb-4">
      <!-- OAuth Requests chart -->
      <div class="lg:col-span-2 bg-card border border-border rounded-lg p-4 shadow-sm">
        <div class="flex justify-between items-center mb-4">
          <span class="text-sm font-semibold text-foreground">OAuth Requests</span>
          <span class="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded-sm">Last 24h</span>
        </div>

        <!-- Bar chart -->
        <div class="h-48 bg-muted/30 rounded-md border border-border flex items-end gap-1 px-3 pb-3">
          <div
            v-for="(height, i) in chartBars"
            :key="i"
            class="flex-1 rounded-sm bg-foreground/20 min-h-1 transition-all duration-300"
            :style="{ height: height + '%' }"
          />
        </div>

        <!-- X-axis -->
        <div class="flex justify-between text-[10px] text-muted-foreground mt-1 px-1">
          <span>0</span>
          <span>6</span>
          <span>12</span>
          <span>18</span>
          <span>23</span>
        </div>

        <!-- Legend -->
        <div class="flex gap-4 mt-3">
          <span class="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span class="h-2 w-2 rounded-full bg-foreground inline-block shrink-0" />
            Authorizations
          </span>
          <span class="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span class="h-2 w-2 rounded-full bg-muted-foreground inline-block shrink-0" />
            Tokens
          </span>
        </div>
      </div>

      <!-- Top Clients -->
      <div class="bg-card border border-border rounded-lg p-4 shadow-sm">
        <div class="flex justify-between items-center mb-4">
          <span class="text-sm font-semibold text-foreground">Top Clients by Usage</span>
          <span class="text-xs bg-muted text-muted-foreground px-2 py-0.5 rounded-sm">Today</span>
        </div>

        <div class="space-y-3">
          <div v-for="client in topClients" :key="client.name">
            <div class="flex justify-between items-center text-sm mb-1">
              <span class="font-medium text-foreground text-xs truncate">{{ client.name }}</span>
              <span class="text-muted-foreground text-xs shrink-0 ml-2">{{ client.count }}</span>
            </div>
            <div class="h-1 w-full bg-muted rounded-full overflow-hidden">
              <div
                class="h-full bg-foreground rounded-full transition-all duration-500"
                :style="{ width: client.pct + '%' }"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Bottom two-column: Activity + Alerts ── -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <!-- Recent Activity -->
      <div class="bg-card border border-border rounded-lg p-4 shadow-sm">
        <div class="flex justify-between items-center mb-3">
          <span class="text-sm font-semibold text-foreground">Recent Activity</span>
          <button class="text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors">
            View all
          </button>
        </div>

        <div>
          <div
            v-for="(item, i) in recentActivity"
            :key="i"
            class="flex items-start gap-2.5 py-2.5"
            :class="{ 'border-t border-border/50': i > 0 }"
          >
            <span
              class="h-2 w-2 rounded-full mt-1.5 shrink-0"
              :class="dotClass[item.type]"
            />
            <div class="flex-1 min-w-0">
              <p class="text-sm text-foreground">{{ item.desc }}</p>
              <p class="text-xs text-muted-foreground mt-0.5">{{ item.client }}</p>
            </div>
            <span class="text-xs text-muted-foreground shrink-0 mt-0.5">{{ item.time }}</span>
          </div>
        </div>
      </div>

      <!-- Security Alerts -->
      <div class="bg-card border border-border rounded-lg p-4 shadow-sm">
        <div class="flex justify-between items-center mb-3">
          <span class="text-sm font-semibold text-foreground">Security Alerts</span>
          <RouterLink
            to="/admin/security"
            class="text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors"
          >
            View all
          </RouterLink>
        </div>

        <div class="space-y-2">
          <div
            v-for="alert in alerts"
            :key="alert.title"
            class="flex items-start gap-2.5 p-2.5 rounded-md bg-muted/30"
          >
            <!-- Accent bar -->
            <div
              class="w-0.5 self-stretch rounded-full shrink-0"
              :class="accentClass[alert.severity]"
            />
            <!-- Content -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-1.5 mb-0.5 flex-wrap">
                <span
                  class="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-sm"
                  :class="badgeClass[alert.severity]"
                >
                  {{ alert.severity }}
                </span>
                <span class="text-sm font-medium text-foreground">{{ alert.title }}</span>
              </div>
              <p class="text-xs text-muted-foreground mt-0.5">{{ alert.desc }}</p>
              <p class="text-xs text-muted-foreground mt-1">{{ alert.time }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
