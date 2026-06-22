<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Activity, AlertTriangle, CalendarDays, ChevronDown, Key, Timer } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { analyticsMock } from '@/api/mocks/analyticsMocks'
import { useAdminStore } from '@/stores/admin'

const adminStore = useAdminStore()

onMounted(async () => {
  await adminStore.fetchAnalytics()
})

const data = computed(() => adminStore.analyticsData ?? analyticsMock)
const errors = computed(() => data.value.errorBreakdown)
const tokenDistribution = computed(() => data.value.tokenDistribution)
const grants = computed(() => data.value.grantTypes)

// ── Stat cards — augmented with icons (UI-only) ───────────────────────────────
const statIcons = [Activity, Key, Timer, AlertTriangle]
const stats = computed(() => data.value.stats.map((s, i) => ({ ...s, icon: statIcons[i] })))

// ── Token distribution display items ──────────────────────────────────────────
const tokens = computed(() => [
  { label: 'Active',  count: `${tokenDistribution.value.active}%`,  dotClass: 'bg-foreground'       },
  { label: 'Expired', count: `${tokenDistribution.value.expired}%`, dotClass: 'bg-muted-foreground'  },
  { label: 'Revoked', count: `${tokenDistribution.value.revoked}%`, dotClass: 'bg-border'            },
])

// ── SVG chart data (y: 0=top, 200=bottom; lower y = higher value) ──────────────
// viewBox "0 0 600 200"  —  7 days × 100px apart
const authPoints  = '0,82  100,58  200,100 300,46  400,70  500,52  600,38'
const tokenPoints = '0,118 100,108 200,132 300,98  400,118 500,112 600,102'
const refreshPts  = '0,154 100,146 200,162 300,140 400,157 500,148 600,143'
const authFill    = `${authPoints} 600,200 0,200`
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 space-y-8 font-sans">
    <!-- ── Header ── -->
    <header class="flex items-start justify-between animate-fade-up">
      <div>
        <h1 class="text-2xl font-semibold text-foreground tracking-tight">Analytics</h1>
        <p class="text-sm text-muted-foreground mt-1">Request volume, token activity, and error rates.</p>
      </div>
      <Button variant="outline" size="sm" class="h-9 gap-1.5 font-medium" aria-label="Select date range">
        <CalendarDays class="h-4 w-4" aria-hidden="true" />
        Last 7 days
        <ChevronDown class="h-4 w-4" aria-hidden="true" />
      </Button>
    </header>

    <!-- ── Stat Cards ── -->
    <section aria-label="Analytics summary" class="grid grid-cols-2 lg:grid-cols-4 gap-4">
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
            <component :is="card.icon" class="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          </div>
        </div>
        <p class="text-2xl sm:text-3xl font-bold text-foreground tabular-nums">
          {{ card.value }}
        </p>
        <p class="text-xs font-semibold text-green-600 dark:text-green-400">{{ card.trend }}</p>
      </div>
    </section>

    <!-- ── Request Trends ── -->
    <section aria-label="Request trends chart" class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-2">
      <div class="flex justify-between items-center mb-5">
        <div>
          <h2 class="text-base font-semibold text-foreground">Request Trends</h2>
          <p class="text-xs text-muted-foreground mt-0.5">Mon – Sun</p>
        </div>
        <span class="text-xs font-medium bg-muted text-muted-foreground px-3 py-1 rounded-full">Last 7 days</span>
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
        <div class="absolute left-0 top-0 text-[10px] font-medium text-muted-foreground leading-none">75K</div>
        <div class="absolute left-0 text-[10px] font-medium text-muted-foreground leading-none" style="top: 33%">50K</div>
        <div class="absolute left-0 text-[10px] font-medium text-muted-foreground leading-none" style="top: 66%">25K</div>
        <div class="absolute left-0 bottom-0 text-[10px] font-medium text-muted-foreground leading-none">0</div>

        <svg class="absolute inset-0 w-full h-full" viewBox="0 0 600 200" preserveAspectRatio="none" overflow="visible">
          <polygon :points="authFill" class="text-foreground" fill="currentColor" fill-opacity="0.05" />
          <polyline :points="authPoints" class="text-foreground" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
          <polyline :points="tokenPoints" class="text-muted-foreground" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round" />
          <polyline :points="refreshPts" class="text-border" fill="none" stroke="currentColor" stroke-width="1" stroke-linejoin="round" stroke-linecap="round" />
        </svg>
      </div>

      <!-- X-axis -->
      <div class="flex justify-between text-xs font-medium text-muted-foreground mt-2 px-1">
        <span>Mon</span><span>Tue</span><span>Wed</span>
        <span>Thu</span><span>Fri</span><span>Sat</span><span>Sun</span>
      </div>

      <!-- Legend -->
      <div class="flex gap-6 mt-4 pt-3 border-t border-border/50">
        <span class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
          <span class="h-2.5 w-4 rounded-full bg-foreground shrink-0" />
          Authorizations
        </span>
        <span class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
          <span class="h-2.5 w-4 rounded-full bg-muted-foreground shrink-0" />
          Tokens
        </span>
        <span class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
          <span class="h-2.5 w-4 rounded-full bg-border shrink-0" />
          Refresh
        </span>
      </div>
    </section>

    <!-- ── Two-column: Error Breakdown + Token Distribution ── -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <!-- Error Breakdown -->
      <div class="bg-card border border-border rounded-xl shadow-sm p-6">
        <div class="mb-5">
          <h2 class="text-base font-semibold text-foreground">Error Breakdown</h2>
          <p class="text-xs text-muted-foreground mt-0.5">Top error categories</p>
        </div>
        <div class="space-y-4">
          <div v-for="err in errors" :key="err.label">
            <div class="flex justify-between items-center mb-1.5">
              <span class="text-sm font-medium text-foreground">{{ err.label }}</span>
              <span class="text-xs font-medium text-muted-foreground tabular-nums">
                {{ err.count }} &nbsp;·&nbsp; {{ err.pct }}%
              </span>
            </div>
            <div class="h-1.5 bg-muted rounded-full">
              <div class="h-full bg-foreground/70 rounded-full transition-all duration-500" :style="{ width: err.pct + '%' }" />
            </div>
          </div>
        </div>
      </div>

      <!-- Token Distribution -->
      <div class="bg-card border border-border rounded-xl shadow-sm p-6">
        <div class="mb-5">
          <h2 class="text-base font-semibold text-foreground">Token Distribution</h2>
          <p class="text-xs text-muted-foreground mt-0.5">Active, expired & revoked</p>
        </div>

        <!-- CSS fake donut -->
        <div class="relative h-40 flex items-center justify-center mb-5">
          <div class="h-36 w-36 rounded-full border-8 border-foreground/20 flex items-center justify-center">
            <div class="h-24 w-24 rounded-full border-8 border-foreground flex items-center justify-center">
              <div class="text-center leading-tight">
                <p class="text-sm font-bold text-foreground">60.8K</p>
                <p class="text-xs font-medium text-muted-foreground">tokens</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Legend -->
        <div class="space-y-2.5 pt-3 border-t border-border/50">
          <div v-for="t in tokens" :key="t.label" class="flex justify-between items-center">
            <span class="flex items-center gap-2 text-sm font-medium text-foreground">
              <span class="h-2 w-2 rounded-full shrink-0" :class="t.dotClass" />
              {{ t.label }}
            </span>
            <span class="text-sm font-bold text-foreground tabular-nums">{{ t.count }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Grant Type Usage ── -->
    <section aria-label="Grant type distribution" class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-4">
      <div class="mb-5">
        <h2 class="text-base font-semibold text-foreground">Grant Type Usage</h2>
        <p class="text-xs text-muted-foreground mt-0.5">Distribution across OAuth2 flows</p>
      </div>
      <div class="space-y-4">
        <div v-for="grant in grants" :key="grant.label">
          <div class="flex justify-between items-center mb-1.5">
            <span class="text-sm font-medium text-foreground">{{ grant.label }}</span>
            <span class="text-xs font-medium text-muted-foreground tabular-nums">{{ grant.pct }}%</span>
          </div>
          <div class="h-1.5 bg-muted rounded-full">
            <div class="h-full bg-foreground/70 rounded-full transition-all duration-500" :style="{ width: grant.pct + '%' }" />
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
@media (prefers-reduced-motion: reduce) {
  .animate-fade-up {
    animation: none !important;
  }
}
</style>
