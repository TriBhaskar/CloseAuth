<script setup lang="ts">
// FE-1.10 (spec §4.2): "Sidebar: 240px fixed [...] The tenant console shows
// the tenant name + Tenant ID chip under the wordmark; the platform console
// shows a PLATFORM label instead." The wordmark is always "CloseAuth"; what
// differs is the line under it — a real IdentifierChip(kind="tenant") for
// the tenant console (the tenant's ID, the only tenant-identifying value
// this shell actually has — no tenant *display name* is fetched anywhere
// in this codebase yet, and fabricating one would violate this codebase's
// own no-fake-data convention) or the literal "PLATFORM" label.
import type { Component } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import ConsoleNavList, { type ConsoleNavItem } from './ConsoleNavList.vue'

defineProps<{
  navItems: ConsoleNavItem[]
  markIcon: Component
  identityLabel: string
  identityTenantId?: string
  collapsed: boolean
  /** Forced by ConsoleShell's <1024px breakpoint — the manual toggle below has no effect while this is true. */
  forceCollapsed: boolean
}>()

defineEmits<{ (e: 'toggle'): void }>()
</script>

<template>
  <aside
    class="flex flex-col h-full shrink-0 overflow-hidden transition-all duration-200 ease-in-out bg-surface border-r border-line"
    :class="collapsed || forceCollapsed ? 'w-[60px]' : 'w-[240px]'"
  >
    <div
      class="flex items-center shrink-0 border-b border-line"
      :class="collapsed || forceCollapsed ? 'h-14 justify-center px-0' : 'min-h-14 py-2.5 px-4 flex-col items-start justify-center gap-1'"
    >
      <template v-if="collapsed || forceCollapsed">
        <div class="h-7 w-7 rounded-md bg-surface-sunken flex items-center justify-center shrink-0">
          <component :is="markIcon" class="h-4 w-4 text-ink" />
        </div>
      </template>
      <template v-else>
        <span class="text-sm font-semibold text-ink tracking-tight">CloseAuth</span>
        <IdentifierChip v-if="identityTenantId" kind="tenant" :value="identityTenantId" />
        <span v-else class="text-eyebrow text-ink-muted">{{ identityLabel }}</span>
      </template>
    </div>

    <ConsoleNavList :nav-items="navItems" :collapsed="collapsed || forceCollapsed" />

    <div v-if="!forceCollapsed" class="px-2 py-2 shrink-0 border-t border-line">
      <button
        type="button"
        class="h-8 w-full flex items-center rounded-md text-ink-muted hover:bg-surface-sunken hover:text-ink transition-colors text-xs gap-1.5"
        :class="collapsed ? 'justify-center' : 'px-2'"
        @click="$emit('toggle')"
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
</template>
