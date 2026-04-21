<script setup lang="ts">
import { computed, ref } from 'vue'
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

// ── Types ──────────────────────────────────────────────────────────────────────
type UserRole   = 'Admin' | 'Moderator' | 'User'
type UserStatus = 'Active' | 'Inactive'

interface User {
  name: string
  email: string
  role: UserRole
  status: UserStatus
  lastLogin: string
  created: string
}

// ── Static data ────────────────────────────────────────────────────────────────
const users: User[] = [
  { name: 'Alice Johnson', email: 'alice@company.com', role: 'User',      status: 'Active',   lastLogin: '2 min ago',  created: '3 months ago' },
  { name: 'Bob Smith',     email: 'bob@company.com',   role: 'Admin',     status: 'Active',   lastLogin: '1 hour ago', created: '8 months ago' },
  { name: 'Carol Davis',   email: 'carol@company.com', role: 'User',      status: 'Inactive', lastLogin: '2 weeks ago',created: '6 months ago' },
  { name: 'David Wilson',  email: 'david@company.com', role: 'User',      status: 'Active',   lastLogin: 'Yesterday',  created: '5 months ago' },
  { name: 'Eva Brown',     email: 'eva@company.com',   role: 'Moderator', status: 'Active',   lastLogin: '3 hours ago',created: '4 months ago' },
]

// ── State ──────────────────────────────────────────────────────────────────────
const search = ref('')

const filteredUsers = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return users
  return users.filter((u) =>
    u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q),
  )
})

// ── Helpers ────────────────────────────────────────────────────────────────────
const avatarColors = ['bg-zinc-600', 'bg-slate-600', 'bg-stone-500', 'bg-zinc-500', 'bg-slate-500']
const initials = (name: string) =>
  name.split(' ').map((p) => p[0]).join('').slice(0, 2).toUpperCase()

// ── Style maps ─────────────────────────────────────────────────────────────────
const roleBadgeClass: Record<UserRole, string> = {
  Admin:     'bg-foreground text-background',
  Moderator: 'bg-muted text-foreground border border-border',
  User:      'bg-muted text-muted-foreground',
}

const statusConfig: Record<UserStatus, { dot: string; pill: string }> = {
  Active:   { dot: 'bg-emerald-500', pill: 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200' },
  Inactive: { dot: 'bg-zinc-300',    pill: 'bg-zinc-100 text-zinc-400 ring-1 ring-inset ring-zinc-200' },
}

// ── Stat cards ─────────────────────────────────────────────────────────────────
const stats = [
  { label: 'Total Users',    value: '12,847', trend: '+324 this month', icon: Users,      trendUp: true  },
  { label: 'Active Users',   value: '11,234', trend: '87% active',     icon: UserCheck,  trendUp: true  },
  { label: 'Administrators', value: '24',     trend: 'Secure access',   icon: Shield,     trendUp: null  },
  { label: 'New This Week',  value: '156',    trend: '+12% growth',     icon: TrendingUp, trendUp: true  },
]
</script>

<template>
  <div class="space-y-8">
    <!-- ── Page Header ── -->
    <div class="flex items-center justify-between">
      <div class="pb-2">
        <h1 class="text-2xl font-bold text-foreground tracking-tight">Users</h1>
        <p class="text-sm text-muted-foreground mt-1">Manage user accounts and permissions.</p>
      </div>
      <Button variant="default" size="sm" class="h-9 px-4 font-medium">
        <Plus class="h-4 w-4 mr-1.5" />
        Add user
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
        <p class="text-xs mt-1">
          <span :class="card.trendUp ? 'text-green-600' : 'text-muted-foreground'">
            {{ card.trend }}
          </span>
        </p>
      </div>
    </div>

    <!-- ── Toolbar ── -->
    <div class="flex items-center gap-3">
      <div class="relative w-72">
        <Search class="h-3.5 w-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/70 pointer-events-none" />
        <Input
          v-model="search"
          class="pl-9 h-9 text-sm"
          placeholder="Search by name or email…"
        />
      </div>
      <span class="text-xs text-muted-foreground ml-auto">
        {{ filteredUsers.length }} user{{ filteredUsers.length !== 1 ? 's' : '' }}
      </span>
    </div>

    <!-- ── Table Card ── -->
    <div class="rounded-xl border border-border/70 bg-card shadow-sm overflow-hidden">
      <table class="w-full border-collapse">
        <thead>
          <tr class="bg-muted/30 border-b border-border/60">
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">User</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Role</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Status</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Last Login</th>
            <th class="text-left px-5 py-2.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">Created</th>
            <th class="px-5 py-2.5 w-10" />
          </tr>
        </thead>
        <tbody class="divide-y divide-border/40">
          <tr
            v-for="(user, i) in filteredUsers"
            :key="user.email"
            class="group hover:bg-muted/20 transition-colors duration-100"
            :class="{ 'opacity-60': user.status === 'Inactive' }"
          >
            <!-- User -->
            <td class="px-5 py-3.5">
              <div class="flex items-center gap-3">
                <!-- Avatar -->
                <div
                  class="h-8 w-8 rounded-full flex items-center justify-center text-xs font-semibold text-white shrink-0"
                  :class="avatarColors[i % avatarColors.length]"
                >
                  {{ initials(user.name) }}
                </div>
                <div>
                  <p class="text-sm font-medium text-foreground leading-tight">{{ user.name }}</p>
                  <p class="text-xs text-muted-foreground mt-0.5">{{ user.email }}</p>
                </div>
              </div>
            </td>

            <!-- Role -->
            <td class="px-5 py-3.5">
              <span
                class="inline-block text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-sm"
                :class="roleBadgeClass[user.role]"
              >
                {{ user.role }}
              </span>
            </td>

            <!-- Status -->
            <td class="px-5 py-3.5">
              <span
                class="inline-flex items-center gap-1.5 text-[0.72rem] font-semibold px-2 py-0.5 rounded-md"
                :class="statusConfig[user.status].pill"
              >
                <span
                  class="h-1.5 w-1.5 rounded-full shrink-0"
                  :class="statusConfig[user.status].dot"
                />
                {{ user.status }}
              </span>
            </td>

            <!-- Last Login -->
            <td class="px-5 py-3.5">
              <span class="text-xs text-muted-foreground/70">{{ user.lastLogin }}</span>
            </td>

            <!-- Created -->
            <td class="px-5 py-3.5">
              <span class="text-xs text-muted-foreground/70">{{ user.created }}</span>
            </td>

            <!-- Actions -->
            <td class="px-4 py-3.5 text-right">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <Button
                    variant="ghost"
                    size="icon"
                    class="h-7 w-7 rounded-md opacity-0 group-hover:opacity-100 transition-opacity hover:bg-muted"
                  >
                    <MoreHorizontal class="h-3.5 w-3.5 text-muted-foreground" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" class="w-40">
                  <DropdownMenuItem class="text-[0.8rem] cursor-pointer">View profile</DropdownMenuItem>
                  <DropdownMenuItem class="text-[0.8rem] cursor-pointer">Edit role</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem class="text-[0.8rem] text-red-500 focus:text-red-600 focus:bg-red-50 cursor-pointer">
                    Deactivate
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </td>
          </tr>

          <!-- Empty state -->
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
