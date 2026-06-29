<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { MoreHorizontal, Plus, Search, Shield, TrendingUp, UserCheck, Users } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { usersMock } from '@/api/mocks/usersMocks'
import { useAdminStore } from '@/stores/admin'
import type { UserRole, UserStatus } from '@/api/models'

const adminStore = useAdminStore()

onMounted(async () => {
  await adminStore.fetchUsers()
})

// Use API data if available, else mock
const data = computed(() => adminStore.usersData ?? usersMock)
const users = computed(() => data.value.users)

// ── Stat cards — augmented with icons (UI-only) ───────────────────────────────
const statIcons = [Users, UserCheck, Shield, TrendingUp]
const statTrends = ['+324 this month', '87% active', 'Secure access', '+12% growth']
const stats = computed(() => data.value.stats.map((s, i) => ({ ...s, icon: statIcons[i], trendUp: true, trend: statTrends[i] })))

// ── State ──────────────────────────────────────────────────────────────────────
const search = ref('')

const filteredUsers = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return users.value
  return users.value.filter((u) =>
    `${u.firstName} ${u.lastName}`.toLowerCase().includes(q) || u.email.toLowerCase().includes(q),
  )
})

// ── Helpers ────────────────────────────────────────────────────────────────────
const avatarColors = ['bg-zinc-600', 'bg-slate-600', 'bg-stone-500', 'bg-zinc-500', 'bg-slate-500']
const initials = (u: { firstName: string; lastName: string }) =>
  `${u.firstName[0]}${u.lastName[0]}`.toUpperCase()

// ── Style maps ─────────────────────────────────────────────────────────────────
const roleBadgeClass: Record<UserRole, string> = {
  Admin:     'bg-foreground text-background',
  Moderator: 'bg-muted text-foreground border border-border',
  User:      'bg-muted text-muted-foreground',
}

const statusConfig: Record<UserStatus, { dot: string; pill: string }> = {
  Active:   { dot: 'bg-emerald-500', pill: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-1 ring-inset ring-emerald-500/20' },
  Inactive: { dot: 'bg-zinc-300 dark:bg-zinc-600', pill: 'bg-muted text-muted-foreground ring-1 ring-inset ring-border' },
}
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 space-y-8 font-sans">
    <!-- ── Page Header ── -->
    <header class="flex items-center justify-between animate-fade-up">
      <div>
        <h1 class="text-2xl font-semibold text-foreground tracking-tight">Users</h1>
        <p class="text-sm text-muted-foreground mt-1">Manage user accounts and permissions.</p>
      </div>
      <RouterLink to="/admin/users/new">
        <Button variant="default" size="sm" class="h-9 px-4 font-semibold gap-1.5">
          <Plus class="h-4 w-4" aria-hidden="true" />
          Add user
        </Button>
      </RouterLink>
    </header>

    <!-- ── Stat Cards ── -->
    <section aria-label="User statistics" class="grid grid-cols-2 lg:grid-cols-4 gap-4">
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
        <p class="text-xs font-semibold" :class="card.trendUp ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'">
          {{ card.trend }}
        </p>
      </div>
    </section>

    <!-- ── Toolbar ── -->
    <div class="flex items-center gap-3 animate-fade-up stagger-2">
      <div class="relative w-72">
        <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" aria-hidden="true" />
        <Input v-model="search" class="pl-9 h-9 text-sm" placeholder="Search by name or email…" aria-label="Search users" />
      </div>
      <span class="text-xs font-medium text-muted-foreground ml-auto tabular-nums">
        {{ filteredUsers.length }} user{{ filteredUsers.length !== 1 ? 's' : '' }}
      </span>
    </div>

    <!-- ── Table Card ── -->
    <div class="rounded-xl border border-border/70 bg-card shadow-sm overflow-hidden animate-fade-up stagger-3" role="region" aria-label="Users table">
      <table class="w-full border-collapse">
        <thead>
          <tr class="bg-muted/30 border-b border-border/60">
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">User</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Role</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Status</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Last Login</th>
            <th class="text-left px-5 py-3 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Created</th>
            <th class="px-5 py-3 w-10" />
          </tr>
        </thead>
        <tbody class="divide-y divide-border/40">
          <tr
            v-for="(user, i) in filteredUsers"
            :key="user.email"
            class="group hover:bg-muted/20 transition-colors duration-100"
            :class="{ 'opacity-60': user.status === 'Inactive' }"
          >
            <td class="px-5 py-4">
              <div class="flex items-center gap-3">
                <div
                  class="h-8 w-8 rounded-full flex items-center justify-center text-xs font-semibold text-white shrink-0"
                  :class="avatarColors[i % avatarColors.length]"
                >
                  {{ initials(user) }}
                </div>
                <div>
                  <p class="text-sm font-semibold text-foreground leading-tight">{{ user.firstName }} {{ user.lastName }}</p>
                  <p class="text-xs text-muted-foreground mt-0.5">{{ user.email }}</p>
                </div>
              </div>
            </td>

            <td class="px-5 py-4">
              <span class="inline-block text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md" :class="roleBadgeClass[user.role]">
                {{ user.role }}
              </span>
            </td>

            <td class="px-5 py-4">
              <span class="inline-flex items-center gap-1.5 text-xs font-semibold px-2 py-0.5 rounded-md" :class="statusConfig[user.status].pill">
                <span class="h-1.5 w-1.5 rounded-full shrink-0" :class="statusConfig[user.status].dot" />
                {{ user.status }}
              </span>
            </td>

            <td class="px-5 py-4">
              <span class="text-xs font-medium text-muted-foreground/70 tabular-nums">{{ user.lastLogin }}</span>
            </td>

            <td class="px-5 py-4">
              <span class="text-xs font-medium text-muted-foreground/70 tabular-nums">{{ user.createdAt }}</span>
            </td>

            <td class="px-4 py-4 text-right">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <Button variant="ghost" size="icon" class="h-7 w-7 rounded-md opacity-0 group-hover:opacity-100 transition-opacity hover:bg-muted">
                    <MoreHorizontal class="h-3.5 w-3.5 text-muted-foreground" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" class="w-40">
                  <DropdownMenuItem class="text-sm cursor-pointer">View profile</DropdownMenuItem>
                  <DropdownMenuItem class="text-sm cursor-pointer">Edit role</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem class="text-sm text-red-500 focus:text-red-600 focus:bg-red-50 cursor-pointer">Deactivate</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </td>
          </tr>

          <tr v-if="filteredUsers.length === 0">
            <td colspan="6">
              <div class="flex flex-col items-center justify-center py-16 gap-2.5">
                <div class="h-12 w-12 rounded-xl bg-muted/50 flex items-center justify-center border border-border/50">
                  <Users class="h-5 w-5 text-muted-foreground/40" />
                </div>
                <p class="text-sm font-semibold text-foreground mt-1">No users found</p>
                <p class="text-xs text-muted-foreground/60">Try adjusting your search.</p>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
@media (prefers-reduced-motion: reduce) {
  .animate-fade-up {
    animation: none !important;
  }
}
</style>
