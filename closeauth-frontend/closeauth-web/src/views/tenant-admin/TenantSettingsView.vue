<script setup lang="ts">
// FE-5.3–5.7 (spec §6.4.7): the settings page, rebuilt from Stage UI-3e's
// two-stacked-card view into the four-tab structure the spec actually wants
// (Branding · Registration · Sessions & tokens · Tenant profile). This file
// is now a thin shell — tab wiring + the dirty-state guard — with each
// tab's real logic living in views/tenant-admin/settings/*.vue, the same
// list-then-detail-style split TenantClientDetailView.vue already
// established for its own three tabs (tab held in the URL query, `data-tab`
// as the stable test/CSS hook since TabsTrigger generates its own id).
//
// FE-5.7's dirty-state guard is owned HERE, not per tab: it protects two
// different things with the SAME ConfirmDialog — leaving the route entirely
// (onBeforeRouteLeave, useDirtyGuard's own header explains why this shell is
// the right place to register it) and switching tabs (which never triggers
// a route change at all, since tabs are a query param, not a route — a
// tab-switch guard has to live wherever the tab state itself lives). Only
// Branding and Registration ever report dirty; Sessions and Tenant profile
// have no editable form to lose.
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import BrandingSettingsTab from './settings/BrandingSettingsTab.vue'
import RegistrationSettingsTab from './settings/RegistrationSettingsTab.vue'
import SessionsSettingsTab from './settings/SessionsSettingsTab.vue'
import TenantProfileSettingsTab from './settings/TenantProfileSettingsTab.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

type SettingsTab = 'branding' | 'registration' | 'sessions' | 'profile'
const VALID_TABS: SettingsTab[] = ['branding', 'registration', 'sessions', 'profile']

function initialTab(): SettingsTab {
  const q = route.query.tab
  return typeof q === 'string' && VALID_TABS.includes(q as SettingsTab)
    ? (q as SettingsTab)
    : 'branding'
}

const activeTab = ref<SettingsTab>(initialTab())

// Whichever tab is currently mounted and edited is the only one that can be
// dirty — an inactive tab's own component instance (and its local reactive
// form state) is unmounted by reka-ui's Tabs (unmountOnHide, the default),
// so there is nothing left of it to protect once it's no longer active.
const isDirty = ref(false)
const { confirmOpen, guardTabChange, confirmDiscard, cancelDiscard } = useDirtyGuard(isDirty)

function handleTabChange(next: string | number): void {
  const target = next as SettingsTab
  guardTabChange(() => {
    activeTab.value = target
    void router.replace({ query: { ...route.query, tab: target } })
  })
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Settings</h1>
      <p class="text-sm text-muted-foreground">
        Branding for the hosted pages, registration, sessions, and this tenant's profile.
      </p>
    </div>

    <Tabs :model-value="activeTab" @update:model-value="handleTabChange">
      <TabsList>
        <TabsTrigger data-tab="branding" value="branding">Branding</TabsTrigger>
        <TabsTrigger data-tab="registration" value="registration">Registration</TabsTrigger>
        <TabsTrigger data-tab="sessions" value="sessions">Sessions &amp; tokens</TabsTrigger>
        <TabsTrigger data-tab="profile" value="profile">Tenant profile</TabsTrigger>
      </TabsList>

      <TabsContent value="branding">
        <BrandingSettingsTab :slug="slug" @update:dirty="(d: boolean) => (isDirty = d)" />
      </TabsContent>
      <TabsContent value="registration">
        <RegistrationSettingsTab :slug="slug" @update:dirty="(d: boolean) => (isDirty = d)" />
      </TabsContent>
      <TabsContent value="sessions">
        <SessionsSettingsTab />
      </TabsContent>
      <TabsContent value="profile">
        <TenantProfileSettingsTab :slug="slug" />
      </TabsContent>
    </Tabs>

    <ConfirmDialog
      :open="confirmOpen"
      title="Discard changes?"
      description="You have unsaved changes on this tab. Leaving now discards them — nothing here saves automatically."
      confirm-label="Discard"
      :destructive="true"
      @update:open="(v: boolean) => !v && cancelDiscard()"
      @confirm="confirmDiscard"
    />
  </div>
</template>
