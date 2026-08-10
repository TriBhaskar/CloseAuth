<script setup lang="ts">
// Stage UI-3b: forked from AppSidebar.vue rather than parameterizing it —
// AppSidebar's nav array is hardcoded to platform `/admin/*` paths under a
// "Platform" label (it's also currently unused outside the orphaned,
// unrouted AdminLayout.vue), and this tree is TENANT_ADMIN-scoped
// `/t/{slug}/console/*`, not the platform one. TenantAdminLayout.vue's own
// UI-3a comment called this out: "fork or parameterize AppSidebar when
// there's real navigation to show." Users, Clients, Resource servers
// (UI-3c), Roles (UI-3d), and now Settings + Audit log (UI-3e) complete this
// tree — this is the one place a nav entry goes when a surface lands.
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ChevronLeft, ChevronRight, KeyRound, ScrollText, ServerCog, Settings, ShieldCheck, Users, ShieldEllipsis } from 'lucide-vue-next'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'

const props = defineProps<{ modelValue: boolean; slug: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const collapsed = computed(() => props.modelValue)
const toggle = () => emit('update:modelValue', !collapsed.value)

const navItems = computed(() => [
  { label: 'Users', icon: Users, path: `/t/${props.slug}/console/users` },
  { label: 'Clients', icon: KeyRound, path: `/t/${props.slug}/console/clients` },
  { label: 'Resource servers', icon: ServerCog, path: `/t/${props.slug}/console/resource-servers` },
  { label: 'Roles', icon: ShieldEllipsis, path: `/t/${props.slug}/console/roles` },
  { label: 'Settings', icon: Settings, path: `/t/${props.slug}/console/settings` },
  { label: 'Audit log', icon: ScrollText, path: `/t/${props.slug}/console/audit` },
])

const route = useRoute()
const isActive = (path: string) => route.path === path || route.path.startsWith(path + '/')
</script>

<template>
  <TooltipProvider :delay-duration="300">
    <aside
      class="flex flex-col h-full shrink-0 overflow-hidden transition-all duration-200 ease-in-out bg-sidebar border-r border-sidebar-border shadow-[1px_0_0_oklch(1_0_0/4%)]"
      :class="collapsed ? 'w-[60px]' : 'w-[220px]'"
    >
      <div
        class="h-14 flex items-center shrink-0 border-b border-white/5"
        :class="collapsed ? 'justify-center px-0' : 'px-4 gap-2.5'"
      >
        <div class="h-7 w-7 rounded-md bg-white/10 flex items-center justify-center shrink-0">
          <ShieldCheck class="h-4 w-4 text-white" />
        </div>
        <span
          class="text-sm font-semibold text-white whitespace-nowrap transition-all duration-200 overflow-hidden"
          :class="collapsed ? 'w-0 opacity-0' : 'opacity-100'"
        >
          Console
        </span>
      </div>

      <nav class="flex-1 px-2 py-3 space-y-0.5 overflow-hidden">
        <template v-for="item in navItems" :key="item.path">
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
