<script setup lang="ts">
import { Activity, AlertTriangle, CalendarDays, ChevronDown, Key, Timer } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

// ── Stat cards ─────────────────────────────────────────────────────────────────
const stats = [
  { label: 'Total Requests',    value: '623.4K', trend: '+12.3%',    icon: Activity,      trendUp: true  },
  { label: 'Tokens Issued',     value: '89.2K',  trend: '+8.7%',     icon: Key,           trendUp: true  },
  { label: 'Avg Response Time', value: '45ms',   trend: '5ms faster',icon: Timer,         trendUp: true  },
  { label: 'Error Rate',        value: '0.8%',   trend: '0.2% less', icon: AlertTriangle, trendUp: true  },
]

// ── Error breakdown ────────────────────────────────────────────────────────────
const errors = [
  { label: 'Invalid credentials', count: 245, pct: 42 },
  { label: 'Expired token',       count: 156, pct: 27 },
  { label: 'Invalid redirect',    count:  89, pct: 15 },
  { label: 'Rate limited',        count:  58, pct: 10 },
  { label: 'Other',               count:  35, pct:  6 },
]

// ── Token distribution ─────────────────────────────────────────────────────────
const tokens = [
  { label: 'Active',  count: '45,230', dotClass: 'bg-foreground'         },
  { label: 'Expired', count: '12,450', dotClass: 'bg-muted-foreground'   },
  { label: 'Revoked', count: '3,200',  dotClass: 'bg-border'             },
]

// ── Grant type usage ───────────────────────────────────────────────────────────
const grants = [
  { label: 'Authorization Code', pct: 75 },
  { label: 'Refresh Token',      pct: 45 },
  { label: 'Client Credentials', pct: 25 },
  { label: 'Password',           pct: 15 },
]

// ── SVG chart data (y: 0=top, 200=bottom; lower y = higher value) ──────────────
// viewBox "0 0 600 200"  —  7 days × 100px apart
const authPoints  = '0,82  100,58  200,100 300,46  400,70  500,52  600,38'
const tokenPoints = '0,118 100,108 200,132 300,98  400,118 500,112 600,102'
const refreshPts  = '0,154 100,146 200,162 300,140 400,157 500,148 600,143'
const authFill    = `${authPoints} 600,200 0,200`
</script>

<template>
  <div class="space-y-6">
    <!-- ── Header ── -->
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-2xl font-bold text-foreground tracking-tight">Analytics</h1>
        <p class="text-sm text-muted-foreground mt-1">Request volume, token activity, and error rates.</p>
      </div>
      <Button variant="outline" size="sm" class="h-9 gap-1.5 font-medium">
        <CalendarDays class="h-4 w-4" />
        Last 7 days
        <ChevronDown class="h-4 w-4" />
      </Button>
    </div>

    <!-- ── Stat Cards ── -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <div
        v-for="card in stats"
        :key="card.label"
        class="bg-card border border-border/70 rounded-xl p-4 shadow-sm"
      >
        <div class="flex justify-between items-start">
          <span class="text-xs font-medium text-muted-foreground uppercase tracking-wide leading-tight">
            {{ card.label }}
          </span>
          <div class="h-7 w-7 rounded-md bg-muted flex items-center justify-center shrink-0">
            <component :is="card.icon" class="h-3.5 w-3.5 text-muted-foreground" />
          </div>
        </div>
        <p class="text-2xl font-bold text-foreground mt-2" style="font-variant-numeric: tabular-nums">
          {{ card.value }}
        </p>
        <p class="text-xs text-green-600 mt-1">{{ card.trend }}</p>
      </div>
    </div>

    <!-- ── Request Trends ── -->
    <div class="bg-card border border-border rounded-lg shadow-sm p-4">
      <div class="flex justify-between items-center mb-4">
        <span class="text-sm font-semibold text-foreground">Request trends</span>
        <span class="text-xs bg-muted text-muted-foreground px-2 py-0.5 rounded-sm">Mon – Sun</span>
      </div>

      <!-- Chart area -->
      <div class="relative h-52">
        <!-- Gridlines + Y-axis -->
        <div class="absolute inset-0">
          <div class="absolute w-full h-px bg-border/50" style="top: 0%"   />
          <div class="absolute w-full h-px bg-border/50" style="top: 33%"  />
          <div class="absolute w-full h-px bg-border/50" style="top: 66%"  />
          <div class="absolute w-full h-px bg-border/50" style="top: 100%" />
        </div>
        <div class="absolute left-0 top-0 text-[10px] text-muted-foreground leading-none">75K</div>
        <div class="absolute left-0 text-[10px] text-muted-foreground leading-none" style="top: 33%">50K</div>
        <div class="absolute left-0 text-[10px] text-muted-foreground leading-none" style="top: 66%">25K</div>
        <div class="absolute left-0 bottom-0 text-[10px] text-muted-foreground leading-none">0</div>

        <!-- SVG lines (inset-l-6 to leave room for y-axis labels) -->
        <svg
          class="absolute inset-0 w-full h-full"
          viewBox="0 0 600 200"
          preserveAspectRatio="none"
          overflow="visible"
        >
          <!-- Authorizations fill -->
          <polygon
            :points="authFill"
            class="text-foreground"
            fill="currentColor"
            fill-opacity="0.05"
          />
          <!-- Authorizations line -->
          <polyline
            :points="authPoints"
            class="text-foreground"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
          <!-- Tokens line -->
          <polyline
            :points="tokenPoints"
            class="text-muted-foreground"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
          <!-- Refresh line -->
          <polyline
            :points="refreshPts"
            class="text-border"
            fill="none"
            stroke="currentColor"
            stroke-width="1"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
        </svg>
      </div>

      <!-- X-axis -->
      <div class="flex justify-between text-[10px] text-muted-foreground mt-2 px-1">
        <span>Mon</span><span>Tue</span><span>Wed</span>
        <span>Thu</span><span>Fri</span><span>Sat</span><span>Sun</span>
      </div>

      <!-- Legend -->
      <div class="flex gap-6 mt-3">
        <span class="flex items-center gap-2 text-xs text-muted-foreground">
          <span class="h-2 w-4 rounded-full bg-foreground shrink-0" />
          Authorizations
        </span>
        <span class="flex items-center gap-2 text-xs text-muted-foreground">
          <span class="h-2 w-4 rounded-full bg-muted-foreground shrink-0" />
          Tokens
        </span>
        <span class="flex items-center gap-2 text-xs text-muted-foreground">
          <span class="h-2 w-4 rounded-full bg-border shrink-0" />
          Refresh
        </span>
      </div>
    </div>

    <!-- ── Two-column: Error Breakdown + Token Distribution ── -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <!-- Error Breakdown -->
      <div class="bg-card border border-border rounded-lg shadow-sm p-4">
        <p class="text-sm font-semibold text-foreground mb-4">Error breakdown</p>
        <div class="space-y-3">
          <div v-for="err in errors" :key="err.label">
            <div class="flex justify-between items-center text-sm mb-1.5">
              <span class="text-sm text-foreground">{{ err.label }}</span>
              <span class="text-xs text-muted-foreground tabular-nums">
                {{ err.count }} · {{ err.pct }}%
              </span>
            </div>
            <div class="h-1 bg-muted rounded-full">
              <div
                class="h-full bg-foreground rounded-full transition-all duration-500"
                :style="{ width: err.pct + '%' }"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Token Distribution -->
      <div class="bg-card border border-border rounded-lg shadow-sm p-4">
        <p class="text-sm font-semibold text-foreground mb-4">Token distribution</p>

        <!-- CSS fake donut -->
        <div class="relative h-40 flex items-center justify-center mb-4">
          <!-- Outer ring -->
          <div class="h-36 w-36 rounded-full border-8 border-foreground/20 flex items-center justify-center">
            <!-- Inner ring -->
            <div class="h-24 w-24 rounded-full border-8 border-foreground flex items-center justify-center">
              <!-- Center text -->
              <div class="text-center leading-tight">
                <p class="text-sm font-semibold text-foreground">60.8K</p>
                <p class="text-xs text-muted-foreground">tokens</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Legend -->
        <div class="space-y-2">
          <div
            v-for="t in tokens"
            :key="t.label"
            class="flex justify-between items-center"
          >
            <span class="flex items-center gap-2 text-sm text-foreground">
              <span class="h-2 w-2 rounded-full shrink-0" :class="t.dotClass" />
              {{ t.label }}
            </span>
            <span class="text-sm font-medium text-foreground tabular-nums">{{ t.count }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Grant Type Usage ── -->
    <div class="bg-card border border-border rounded-lg shadow-sm p-4">
      <p class="text-sm font-semibold text-foreground mb-4">Grant type usage</p>
      <div class="space-y-3">
        <div v-for="grant in grants" :key="grant.label">
          <div class="flex justify-between items-center text-sm mb-1.5">
            <span class="text-sm text-foreground">{{ grant.label }}</span>
            <span class="text-xs text-muted-foreground tabular-nums">{{ grant.pct }}%</span>
          </div>
          <div class="h-1 bg-muted rounded-full">
            <div
              class="h-full bg-foreground rounded-full transition-all duration-500"
              :style="{ width: grant.pct + '%' }"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
