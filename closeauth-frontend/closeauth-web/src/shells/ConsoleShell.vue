<script setup lang="ts">
// FE-1.10 (spec §4.2): the shared shell for both the tenant and platform
// consoles — replaces the near-duplicate layouts/TenantAdminLayout.vue +
// layouts/PlatformAdminLayout.vue pairing (each with its own hand-rolled,
// non-responsive sidebar). Session-specific logic (session store, sign-out,
// the platform token-expiry countdown) stays in each console's own thin
// composition root (layouts/TenantAdminLayout.vue, layouts/
// PlatformAdminLayout.vue) — this shell only knows nav items, identity, and
// a title; it has no opinion about which principal type is signed in.
//
// Responsive behaviour is net new (today's sidebars have none): >=1024px
// the manual collapse toggle works normally; 768–1023px the sidebar is
// forced to icon-rail width regardless of the toggle; <768px the sidebar
// isn't rendered as a static column at all — it moves into a Sheet opened
// by a topbar hamburger. `useMediaQuery` (@vueuse/core, already a
// dependency) drives both breakpoints rather than hand-rolling matchMedia
// listeners.
import { ref, type Component } from 'vue'
import { useRoute } from 'vue-router'
import { useMediaQuery } from '@vueuse/core'
import { Menu, Monitor, Moon, Sun } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { useThemeStore } from '@/stores/theme'
import ConsoleSidebar from './ConsoleSidebar.vue'
import ConsoleNavList, { type ConsoleNavItem } from './ConsoleNavList.vue'

defineProps<{
  navItems: ConsoleNavItem[]
  markIcon: Component
  identityLabel: string
  identityTenantId?: string
}>()

const route = useRoute()
const themeStore = useThemeStore()

const collapsed = ref(false)
const mobileNavOpen = ref(false)

const isLgUp = useMediaQuery('(min-width: 1024px)')
const isMdUp = useMediaQuery('(min-width: 768px)')

// §4.2: "Topbar: current page title (left)". Falls back to the app name
// rather than rendering blank if a route doesn't set meta.title (every
// console child route does — see router/index.ts — but a fallback is
// cheap insurance against a future route that forgets to).
const pageTitle = () => (typeof route.meta.title === 'string' ? route.meta.title : 'CloseAuth')
</script>

<template>
  <div class="min-h-screen bg-background text-foreground flex">
    <ConsoleSidebar
      v-if="isMdUp"
      :nav-items="navItems"
      :mark-icon="markIcon"
      :identity-label="identityLabel"
      :identity-tenant-id="identityTenantId"
      :collapsed="collapsed"
      :force-collapsed="!isLgUp"
      @toggle="collapsed = !collapsed"
    />

    <Sheet v-else v-model:open="mobileNavOpen">
      <SheetContent side="left" class="p-0 w-[240px] bg-surface">
        <SheetHeader class="px-4 py-3 border-b border-line">
          <SheetTitle class="text-sm font-semibold text-ink">CloseAuth</SheetTitle>
        </SheetHeader>
        <!-- Plain div wrapper, not @click on the component tag: a native
             click bubbles through real DOM regardless of Vue component
             boundaries, which a listener on <ConsoleNavList> itself isn't
             guaranteed to receive (its root is TooltipProvider, not a
             plain element that reliably forwards fallthrough listeners). -->
        <div @click="mobileNavOpen = false">
          <ConsoleNavList :nav-items="navItems" :collapsed="false" />
        </div>
      </SheetContent>
    </Sheet>

    <div class="flex flex-col flex-1 min-w-0">
      <header class="h-14 flex items-center justify-between px-6 border-b border-border shrink-0 gap-4">
        <div class="flex items-center gap-3 min-w-0">
          <Button
            v-if="!isMdUp"
            variant="ghost"
            size="icon-sm"
            aria-label="Open navigation"
            @click="mobileNavOpen = true"
          >
            <Menu class="size-4" />
          </Button>
          <h1 class="text-page-title font-semibold truncate">{{ pageTitle() }}</h1>
        </div>
        <div class="flex items-center gap-3 shrink-0">
          <slot name="topbar-actions" />
          <Button
            variant="ghost"
            size="icon-sm"
            :aria-label="`Theme: ${themeStore.mode} — click to switch`"
            @click="themeStore.cycle()"
          >
            <Sun v-if="themeStore.mode === 'light'" class="size-4" />
            <Moon v-else-if="themeStore.mode === 'dark'" class="size-4" />
            <Monitor v-else class="size-4" />
          </Button>
        </div>
      </header>
      <main class="flex-grow p-6 overflow-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
