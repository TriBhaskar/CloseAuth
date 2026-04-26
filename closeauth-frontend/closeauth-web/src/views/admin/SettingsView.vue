<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Bell, Globe, Key, Loader2, Lock, Save } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'

// ── State ──────────────────────────────────────────────────────────────────────
const tabs = ['General', 'Security', 'Tokens', 'Notifications'] as const
type Tab = typeof tabs[number]

const activeTab = ref<Tab>('General')
const { isLoading: isSaving, execute } = useAsyncState()

const settings = reactive({
  issuerUrl:       'https://auth.company.com',
  defaultAudience: 'https://api.company.com',
  timezone:        'UTC',
  language:        'English',
})

// ── Save ───────────────────────────────────────────────────────────────────────
// TODO(api): replace mock initial values with adminService.getSettings() on mount
const handleSave = async () => {
  await execute(() => adminService.saveSettings({ ...settings }))
}

// ── Placeholder tab config ─────────────────────────────────────────────────────
const placeholders: Record<Exclude<Tab, 'General'>, { icon: typeof Lock; label: string }> = {
  Security:      { icon: Lock, label: 'Security settings' },
  Tokens:        { icon: Key,  label: 'Token configuration' },
  Notifications: { icon: Bell, label: 'Notification preferences' },
}
</script>

<template>
  <div class="p-6 space-y-10 font-sans max-w-3xl">
    <!-- ── Header ── -->
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-2xl font-bold text-foreground tracking-tight">Settings</h1>
        <p class="text-sm font-medium text-muted-foreground mt-1">Configure your OAuth2 server.</p>
      </div>
      <Button
        variant="default"
        size="sm"
        class="h-9 px-4 font-medium"
        :disabled="isSaving"
        @click="handleSave"
      >
        <Loader2 v-if="isSaving" class="h-4 w-4 mr-2 animate-spin" />
        <Save v-else class="h-4 w-4 mr-2" />
        {{ isSaving ? 'Saving…' : 'Save changes' }}
      </Button>
    </div>

    <!-- ── Tab Bar ── -->
    <div class="flex border-b border-border">
      <button
        v-for="tab in tabs"
        :key="tab"
        class="px-1 py-3 mr-6 text-sm font-medium border-b-2 transition-colors whitespace-nowrap"
        :class="activeTab === tab
          ? 'border-foreground text-foreground font-medium'
          : 'border-transparent text-muted-foreground hover:text-foreground'"
        @click="activeTab = tab"
      >
        {{ tab }}
      </button>
    </div>

    <!-- ── General Tab ── -->
    <div v-if="activeTab === 'General'" class="bg-card border border-border rounded-xl shadow-sm p-6">
      <!-- Card header -->
      <div class="flex items-center gap-2 mb-1">
        <Globe class="h-4 w-4 text-muted-foreground" />
        <span class="text-sm font-semibold text-foreground">General</span>
      </div>
      <div class="h-px bg-border mb-6" />

      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <!-- Issuer URL -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">Issuer URL</Label>
          <Input
            v-model="settings.issuerUrl"
            type="url"
            class="h-9"
            placeholder="https://auth.company.com"
          />
          <p class="text-xs text-muted-foreground">Base URL for token and discovery endpoints.</p>
        </div>

        <!-- Default Audience -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">Default Audience</Label>
          <Input
            v-model="settings.defaultAudience"
            type="url"
            class="h-9"
            placeholder="https://api.company.com"
          />
          <p class="text-xs text-muted-foreground">Default 'aud' claim in access tokens.</p>
        </div>

        <!-- Timezone -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">Timezone</Label>
          <Select v-model="settings.timezone">
            <SelectTrigger class="h-9 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="UTC">UTC</SelectItem>
              <SelectItem value="America/New_York">America/New_York</SelectItem>
              <SelectItem value="Europe/London">Europe/London</SelectItem>
              <SelectItem value="Asia/Tokyo">Asia/Tokyo</SelectItem>
            </SelectContent>
          </Select>
          <p class="text-xs text-muted-foreground">Used for log timestamps and scheduled tasks.</p>
        </div>

        <!-- Default Language -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">Default Language</Label>
          <Select v-model="settings.language">
            <SelectTrigger class="h-9 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="English">English</SelectItem>
              <SelectItem value="Spanish">Spanish</SelectItem>
              <SelectItem value="French">French</SelectItem>
              <SelectItem value="German">German</SelectItem>
            </SelectContent>
          </Select>
          <p class="text-xs text-muted-foreground">Default language for OAuth end-user screens.</p>
        </div>
      </div>
    </div>

    <!-- ── Other tabs: placeholder ── -->
    <template v-else>
      <div class="bg-card border border-border rounded-xl shadow-sm p-6">
        <div class="flex items-center gap-2 mb-1">
          <component :is="placeholders[activeTab as Exclude<Tab, 'General'>].icon" class="h-4 w-4 text-muted-foreground" />
          <span class="text-sm font-semibold text-foreground">{{ activeTab }}</span>
        </div>
        <div class="h-px bg-border mb-1" />

        <div class="flex flex-col items-center justify-center py-16 gap-2.5">
          <div class="h-12 w-12 rounded-xl bg-muted/50 border border-border/50 flex items-center justify-center">
            <component
              :is="placeholders[activeTab as Exclude<Tab, 'General'>].icon"
              class="h-5 w-5 text-muted-foreground/40"
            />
          </div>
          <p class="text-sm font-medium text-foreground mt-1">Coming soon</p>
          <p class="text-xs text-muted-foreground">This section is under development.</p>
        </div>
      </div>
    </template>
  </div>
</template>
