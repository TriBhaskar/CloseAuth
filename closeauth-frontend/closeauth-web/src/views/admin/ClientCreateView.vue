<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ArrowLeft, Loader2, Plus, X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
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
import { clientCreateConfigMock } from '@/api/mocks/settingsMocks'

const router = useRouter()
const { isLoading, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const appName        = ref('')
const description    = ref('')
const appType        = ref('')
const logoUrl        = ref('')
const homepageUrl    = ref('')
const authMethod     = ref('client_secret_basic')
const redirectUris   = ref<string[]>([''])
const selectedScopes = ref<string[]>(['openid'])

// ── Available scopes ───────────────────────────────────────────────────────────
const availableScopes = clientCreateConfigMock.availableScopes

// ── URI helpers ────────────────────────────────────────────────────────────────
const addUri    = () => redirectUris.value.push('')
const removeUri = (i: number) => redirectUris.value.splice(i, 1)

// ── Scope toggle ───────────────────────────────────────────────────────────────
const toggleScope = (key: string) => {
  const idx = selectedScopes.value.indexOf(key)
  if (idx === -1) selectedScopes.value.push(key)
  else selectedScopes.value.splice(idx, 1)
}

// ── Submit ─────────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  const result = await execute(() =>
    adminService.createClient({
      appName:      appName.value,
      description:  description.value,
      appType:      appType.value,
      logoUrl:      logoUrl.value,
      homepageUrl:  homepageUrl.value,
      authMethod:   authMethod.value,
      redirectUris: redirectUris.value.filter(Boolean),
      scopes:       selectedScopes.value,
    }),
  )
  if (result) await router.push('/admin/clients')
}
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 max-w-2xl space-y-8 font-sans">
    <!-- ── Header ── -->
    <header class="animate-fade-up">
      <RouterLink to="/admin/clients">
        <Button variant="ghost" size="sm" class="mb-4 -ml-2 text-muted-foreground hover:text-foreground">
          <ArrowLeft class="h-4 w-4 mr-1" aria-hidden="true" />
          Clients
        </Button>
      </RouterLink>
      <h1 class="text-2xl font-semibold text-foreground tracking-tight">New client</h1>
      <p class="text-sm text-muted-foreground mt-1">Register an OAuth2 application.</p>
    </header>

    <!-- ── CARD 1: Basic information ── -->
    <section aria-labelledby="basic-info-heading" class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-1 transition-shadow hover:shadow-md">
      <h2 id="basic-info-heading" class="text-base font-semibold text-foreground">Basic information</h2>
      <div class="h-px bg-border my-4" />

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <!-- Application Name (full width) -->
        <div class="md:col-span-2 space-y-1.5">
          <Label for="appName" class="text-sm font-medium text-foreground">
            Application Name
            <span class="text-muted-foreground ml-0.5">*</span>
          </Label>
          <Input
            id="appName"
            v-model="appName"
            type="text"
            placeholder="My OAuth App"
            class="h-9"
          />
        </div>

        <!-- Description (full width) -->
        <div class="md:col-span-2 space-y-1.5">
          <Label for="description" class="text-sm font-medium text-foreground">Description</Label>
          <textarea
            id="description"
            v-model="description"
            rows="3"
            placeholder="Brief description of what this application does…"
            class="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] resize-none"
          />
        </div>

        <!-- Application Type -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">
            Application Type
            <span class="text-muted-foreground ml-0.5">*</span>
          </Label>
          <Select v-model="appType">
            <SelectTrigger class="h-9 w-full">
              <SelectValue placeholder="Select type…" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="spa">SPA</SelectItem>
              <SelectItem value="web">Web App</SelectItem>
              <SelectItem value="native">Native</SelectItem>
              <SelectItem value="m2m">Machine-to-Machine</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <!-- Empty right col (grid spacer) -->
        <div class="hidden md:block" />

        <!-- Logo URL -->
        <div class="space-y-1.5">
          <Label for="logoUrl" class="text-sm font-medium text-foreground">Logo URL</Label>
          <Input
            id="logoUrl"
            v-model="logoUrl"
            type="url"
            placeholder="https://example.com/logo.png"
            class="h-9"
          />
          <p class="text-xs text-muted-foreground">Optional. Must be https://</p>
        </div>

        <!-- Homepage URL -->
        <div class="space-y-1.5">
          <Label for="homepageUrl" class="text-sm font-medium text-foreground">Homepage URL</Label>
          <Input
            id="homepageUrl"
            v-model="homepageUrl"
            type="url"
            placeholder="https://example.com"
            class="h-9"
          />
          <p class="text-xs text-muted-foreground">Optional.</p>
        </div>
      </div>
    </section>

    <!-- ── CARD 2: Authorization ── -->
    <section aria-labelledby="auth-heading" class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-2 transition-shadow hover:shadow-md">
      <h2 id="auth-heading" class="text-base font-semibold text-foreground">Authorization</h2>
      <div class="h-px bg-border my-4" />

      <div class="space-y-5">
        <!-- Token Endpoint Auth Method -->
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">Token Endpoint Auth Method</Label>
          <Select v-model="authMethod">
            <SelectTrigger class="h-9 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="client_secret_basic">client_secret_basic</SelectItem>
              <SelectItem value="client_secret_post">client_secret_post</SelectItem>
              <SelectItem value="none">none</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <!-- Redirect URIs -->
        <div class="space-y-2">
          <div>
            <Label class="text-sm font-medium text-foreground">Redirect URIs</Label>
            <p class="text-xs text-muted-foreground mt-0.5">One URI per entry.</p>
          </div>

          <div class="space-y-2">
            <div
              v-for="(_, index) in redirectUris"
              :key="index"
              class="flex gap-2"
            >
              <Input
                v-model="redirectUris[index]"
                type="url"
                placeholder="https://example.com/callback"
                class="flex-1 h-9"
              />
              <Button
                v-if="redirectUris.length > 1"
                type="button"
                variant="ghost"
                size="icon"
                class="h-9 w-9 shrink-0 text-muted-foreground hover:text-foreground"
                @click="removeUri(index)"
              >
                <X class="h-4 w-4" />
              </Button>
            </div>
          </div>

          <Button
            type="button"
            variant="ghost"
            size="sm"
            class="mt-1 -ml-1 text-xs text-muted-foreground hover:text-foreground font-medium"
            @click="addUri"
          >
            <Plus class="h-3.5 w-3.5 mr-1" />
            Add URI
          </Button>
        </div>
      </div>
    </section>

    <!-- ── CARD 3: Scopes ── -->
    <section aria-labelledby="scopes-heading" class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-3 transition-shadow hover:shadow-md">
      <h2 id="scopes-heading" class="text-base font-semibold text-foreground">Scopes & permissions</h2>
      <div class="h-px bg-border my-4" />

      <p class="text-sm text-muted-foreground mb-4">
        Select scopes this client may request.
      </p>

      <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
        <div
          v-for="scope in availableScopes"
          :key="scope.key"
          class="border rounded-lg p-3 cursor-pointer transition-colors select-none"
          :class="selectedScopes.includes(scope.key)
            ? 'border-primary/50 bg-primary/5 dark:bg-primary/10'
            : 'border-border hover:border-muted-foreground'"
          @click="toggleScope(scope.key)"
        >
          <div class="flex items-start gap-2">
            <Checkbox
              :checked="selectedScopes.includes(scope.key)"
              class="mt-0.5 pointer-events-none shrink-0"
            />
            <div class="min-w-0">
              <p class="text-xs font-mono font-semibold text-foreground">{{ scope.key }}</p>
              <p class="text-xs text-muted-foreground mt-0.5">{{ scope.label }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Submit ── -->
    <div class="mt-2 animate-fade-up stagger-4">
      <Button
        type="button"
        variant="default"
        class="w-full h-10 font-medium"
        :disabled="isLoading || !appName.trim() || !appType"
        @click="handleSubmit"
      >
        <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
        {{ isLoading ? 'Registering…' : 'Register application' }}
      </Button>
      <p class="text-xs text-muted-foreground text-center mt-3">
        You can edit these settings after registration.
      </p>
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
