<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  AppWindow,
  BarChart3,
  ChevronLeft,
  ChevronRight,
  LayoutDashboard,
  Settings,
  Shield,
  Users,
} from 'lucide-vue-next'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'

// ── Props / Emits ──────────────────────────────────────────────────────────────
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const collapsed = computed(() => props.modelValue)
const toggle = () => emit('update:modelValue', !collapsed.value)

// ── Nav items ──────────────────────────────────────────────────────────────────
const navItems = [
  { label: 'Overview',  icon: LayoutDashboard, path: '/admin/dashboard' },
  { label: 'Clients',   icon: AppWindow,        path: '/admin/clients'   },
  { label: 'Users',     icon: Users,            path: '/admin/users'     },
  { label: 'Analytics', icon: BarChart3,        path: '/admin/analytics' },
  { label: 'Security',  icon: Shield,           path: '/admin/security'  },
  { label: 'Settings',  icon: Settings,         path: '/admin/settings'  },
]

const route = useRoute()
const isActive = (path: string) => route.path === path || route.path.startsWith(path + '/')
</script>

<template>
  <TooltipProvider :delay-duration="300">
    <aside
      class="flex flex-col h-full shrink-0 overflow-hidden transition-all duration-200 ease-in-out bg-sidebar border-r border-sidebar-border shadow-[1px_0_0_oklch(1_0_0/4%)]"
      :class="collapsed ? 'w-[60px]' : 'w-[220px]'"
    >
      <!-- ── Logo header ── -->
      <div
        class="h-14 flex items-center shrink-0 border-b border-white/5"
        :class="collapsed ? 'justify-center px-0' : 'px-4 gap-2.5'"
      >
        <!-- Icon mark -->
        <div class="h-7 w-7 rounded-md bg-white/10 flex items-center justify-center shrink-0">
          <LayoutDashboard class="h-4 w-4 text-white" />
        </div>
        <!-- Brand name -->
        <span
          class="text-sm font-semibold text-white whitespace-nowrap transition-all duration-200 overflow-hidden"
          :class="collapsed ? 'w-0 opacity-0' : 'opacity-100'"
        >
          CloseAuth
        </span>
      </div>

      <!-- ── Nav ── -->
      <nav class="flex-1 px-2 py-3 space-y-0.5 overflow-hidden">
        <!-- Group label -->
        <p
          class="text-[10px] font-semibold uppercase tracking-widest text-zinc-500 px-2 mb-2 whitespace-nowrap transition-all duration-200 overflow-hidden"
          :class="collapsed ? 'h-0 opacity-0 mb-0' : 'h-auto opacity-100'"
        >
          Platform
        </p>

        <template v-for="item in navItems" :key="item.path">
          <!-- Collapsed: icon only with tooltip -->
          <Tooltip v-if="collapsed">
            <TooltipTrigger as-child>
              <RouterLink
                :to="item.path"
                class="flex items-center justify-center h-9 w-full rounded-md transition-colors duration-150 relative"
                :class="
                  isActive(item.path)
                    ? 'bg-sidebar-primary/20 text-sidebar-foreground'
                    : 'text-sidebar-foreground/50 hover:bg-sidebar-accent hover:text-sidebar-foreground'
                "
              >
                <span
                  v-if="isActive(item.path)"
                  class="absolute left-0 top-1.5 h-[calc(100%-12px)] w-0.5 rounded-full bg-sidebar-primary"
                />
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
              </RouterLink>
            </TooltipTrigger>
            <TooltipContent side="right" class="text-xs">
              {{ item.label }}
            </TooltipContent>
          </Tooltip>

          <!-- Expanded: icon + label -->
          <RouterLink
            v-else
            :to="item.path"
            class="flex items-center gap-3 h-9 px-2.5 rounded-md text-sm transition-colors duration-150 relative"
            :class="
              isActive(item.path)
                ? 'bg-sidebar-primary/20 text-sidebar-foreground font-medium'
                : 'text-sidebar-foreground/50 hover:bg-sidebar-accent hover:text-sidebar-foreground'
            "
          >
            <span
              v-if="isActive(item.path)"
              class="absolute left-0 top-1.5 h-[calc(100%-12px)] w-0.5 rounded-full bg-sidebar-primary"
            />
            <component :is="item.icon" class="h-4 w-4 shrink-0" />
            <span class="whitespace-nowrap">{{ item.label }}</span>
          </RouterLink>
        </template>
      </nav>

      <!-- ── Collapse toggle ── -->
      <div class="px-2 py-2 shrink-0 border-t border-white/5">
        <button
          type="button"
          class="h-8 w-full flex items-center rounded-md text-zinc-500 hover:bg-white/8 hover:text-zinc-300 transition-colors text-xs gap-1.5"
          :class="collapsed ? 'justify-center' : 'px-2'"
          @click="toggle"
        >
          <ChevronLeft v-if="!collapsed" class="h-3.5 w-3.5 shrink-0" />
          <ChevronRight v-else class="h-3.5 w-3.5 shrink-0" />
          <span
            class="whitespace-nowrap overflow-hidden transition-all duration-200"
            :class="collapsed ? 'w-0 opacity-0' : 'opacity-100'"
          >
            Collapse
          </span>
        </button>
      </div>

    </aside>
  </TooltipProvider>
</template>
