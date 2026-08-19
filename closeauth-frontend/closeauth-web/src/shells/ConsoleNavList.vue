<script setup lang="ts">
// FE-1.10: the nav-item rendering shared by ConsoleSidebar's static column
// AND its <768px Sheet content — factored out so the two render paths don't
// duplicate the RouterLink/active-state/tooltip markup.
//
// FE-4a adds optional grouping (spec §4.2: "grouped with 0.6875rem uppercase
// group labels" — DIRECTORY / APPLICATIONS / OPERATIONS on the tenant
// console). `group` is optional and additive: PlatformAdminLayout.vue's flat
// 2-item array (no group set on either) renders exactly as before — no
// headers, one flat list — since groupedSections collapses to a single
// unlabelled section when nothing sets `group`. Items are grouped by
// first-appearance order, not sorted alphabetically, so a caller controls
// the group order simply by the order its own navItems array lists them in.
// Collapsed (icon-rail/tooltip) mode never renders group labels — there's no
// room for the text at 60px, and the tooltip on hover already names the item.
import type { Component } from 'vue'
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'

export interface ConsoleNavItem {
  label: string
  icon: Component
  path: string
  /** Optional §4.2 sidebar group label (e.g. "Directory", "Applications", "Operations"). Omit for a flat list. */
  group?: string
}

interface NavSection {
  label: string | null
  items: ConsoleNavItem[]
}

const props = defineProps<{
  navItems: ConsoleNavItem[]
  collapsed: boolean
}>()

const route = useRoute()
const isActive = (path: string) => route.path === path || route.path.startsWith(path + '/')

const sections = computed<NavSection[]>(() => {
  const order: string[] = []
  const byGroup = new Map<string, ConsoleNavItem[]>()
  for (const item of props.navItems) {
    const key = item.group ?? ''
    if (!byGroup.has(key)) {
      order.push(key)
      byGroup.set(key, [])
    }
    byGroup.get(key)!.push(item)
  }
  return order.map((key) => ({ label: key || null, items: byGroup.get(key)! }))
})
</script>

<template>
  <TooltipProvider :delay-duration="300">
    <nav class="flex-1 px-2 py-3 space-y-3 overflow-hidden">
      <div v-for="section in sections" :key="section.label ?? '__ungrouped'">
        <p
          v-if="section.label && !collapsed"
          class="px-2.5 mb-1 text-eyebrow text-ink-muted uppercase tracking-[0.08em]"
        >
          {{ section.label }}
        </p>
        <div class="space-y-0.5">
          <template v-for="item in section.items" :key="item.path">
            <Tooltip v-if="collapsed">
              <TooltipTrigger as-child>
                <RouterLink
                  :to="item.path"
                  class="flex items-center justify-center h-9 w-full rounded-md transition-colors duration-150 relative"
                  :class="isActive(item.path) ? 'text-primary' : 'text-ink-muted hover:bg-surface-sunken hover:text-ink'"
                >
                  <!-- §4.2: active nav item is an accent left rule + accent
                       text, no fill. -->
                  <span
                    v-if="isActive(item.path)"
                    class="absolute left-0 top-1.5 h-[calc(100%-12px)] w-0.5 rounded-full bg-primary"
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
              :class="isActive(item.path) ? 'text-primary font-medium' : 'text-ink-muted hover:bg-surface-sunken hover:text-ink'"
            >
              <span
                v-if="isActive(item.path)"
                class="absolute left-0 top-1.5 h-[calc(100%-12px)] w-0.5 rounded-full bg-primary"
              />
              <component :is="item.icon" class="h-4 w-4 shrink-0" />
              <span class="whitespace-nowrap">{{ item.label }}</span>
            </RouterLink>
          </template>
        </div>
      </div>
    </nav>
  </TooltipProvider>
</template>
