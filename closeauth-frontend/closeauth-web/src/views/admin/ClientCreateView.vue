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

const router = useRouter()

// ── State ──────────────────────────────────────────────────────────────────────
const appName      = ref('')
const description  = ref('')
const appType      = ref('')
const logoUrl      = ref('')
const homepageUrl  = ref('')
const authMethod   = ref('client_secret_basic')
const redirectUris = ref<string[]>([''])
const selectedScopes = ref<string[]>(['openid'])
const isLoading    = ref(false)

// ── Available scopes ───────────────────────────────────────────────────────────
const availableScopes = [
  { key: 'openid',         label: 'OpenID Connect'      },
  { key: 'email',          label: 'Email Address'       },
  { key: 'profile',        label: 'Profile Information' },
  { key: 'offline_access', label: 'Offline Access'      },
  { key: 'read:users',     label: 'Read Users'          },
  { key: 'write:users',    label: 'Write Users'         },
]

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
  isLoading.value = true
  try {
    const response = await fetch('/api/admin/clients', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        appName:      appName.value,
        description:  description.value,
        appType:      appType.value,
        logoUrl:      logoUrl.value,
        homepageUrl:  homepageUrl.value,
        authMethod:   authMethod.value,
        redirectUris: redirectUris.value.filter(Boolean),
        scopes:       selectedScopes.value,
      }),
    })
    if (!response.ok) throw new Error('Failed to register client')
    await router.push('/admin/clients')
  } catch (err) {
    console.error(err)
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="max-w-2xl space-y-8">
    <!-- ── Header ── -->
    <div>
      <RouterLink to="/admin/clients">
        <Button variant="ghost" size="sm" class="mb-4 -ml-2 text-muted-foreground hover:text-foreground">
          <ArrowLeft class="h-4 w-4 mr-1" />
          Clients
        </Button>
      </RouterLink>
      <h1 class="text-2xl font-bold text-foreground tracking-tight">New client</h1>
      <p class="text-sm text-muted-foreground mt-1">Register an OAuth2 application.</p>
    </div>

    <!-- ── CARD 1: Basic information ── -->
    <div class="bg-card border border-border rounded-xl shadow-sm p-6">
      <h2 class="text-sm font-semibold text-foreground">Basic information</h2>
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
    </div>

    <!-- ── CARD 2: Authorization ── -->
    <div class="bg-card border border-border rounded-xl shadow-sm p-6">
      <h2 class="text-sm font-semibold text-foreground">Authorization</h2>
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
    </div>

    <!-- ── CARD 3: Scopes ── -->
    <div class="bg-card border border-border rounded-xl shadow-sm p-6">
      <h2 class="text-sm font-semibold text-foreground">Scopes & permissions</h2>
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
            ? 'border-foreground bg-muted/50'
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
    </div>

    <!-- ── Submit ── -->
    <div class="mt-2">
      <Button
        type="button"
        variant="default"
        class="w-full h-10 font-medium"
        :disabled="isLoading"
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
