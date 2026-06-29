<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ArrowLeft, Check, Eye, EyeOff, Loader2 } from 'lucide-vue-next'
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
import type { UserRole } from '@/api/models'

const router = useRouter()
const { isLoading, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const firstName   = ref('')
const lastName    = ref('')
const email       = ref('')
const username    = ref('')
const role        = ref<UserRole | ''>('')
const password    = ref('')
const confirmPassword = ref('')
const showPassword    = ref(false)
const showConfirm     = ref(false)
const sendInvite      = ref(true)

// ── Available roles ────────────────────────────────────────────────────────────
const availableRoles: { value: UserRole; label: string; description: string }[] = [
  { value: 'Admin',     label: 'Admin',     description: 'Full system access' },
  { value: 'Moderator', label: 'Moderator', description: 'Manage users and content' },
  { value: 'User',      label: 'User',      description: 'Standard account access' },
]

// ── Password Strength ──────────────────────────────────────────────────────────
const passwordStrength = computed(() => {
  const p = password.value
  if (!p) return 0
  let score = 0
  if (p.length >= 8) score++
  if (/[A-Z]/.test(p)) score++
  if (/[0-9]/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p)) score++
  return score
})

const strengthLabel = computed(() => {
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong']
  return labels[passwordStrength.value]
})

const strengthSegmentColor = (index: number) => {
  if (index >= passwordStrength.value) return 'bg-border'
  const colors = ['bg-red-400', 'bg-amber-400', 'bg-green-400', 'bg-green-500']
  return colors[passwordStrength.value - 1] ?? 'bg-border'
}

const passwordsMatch = computed(
  () => password.value.length > 0 && password.value === confirmPassword.value,
)

// ── Form valid ─────────────────────────────────────────────────────────────────
const isFormValid = computed(() =>
  firstName.value.trim() &&
  lastName.value.trim() &&
  email.value.trim() &&
  role.value &&
  password.value &&
  passwordsMatch.value,
)

// ── Submit ─────────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  if (!role.value) return

  const result = await execute(() =>
    adminService.createUser({
      firstName:  firstName.value,
      lastName:   lastName.value,
      email:      email.value,
      username:   username.value || undefined,
      role:       role.value as UserRole,
      password:   password.value,
      sendInvite: sendInvite.value,
    }),
  )
  if (result) await router.push('/admin/users')
}
</script>

<template>
  <div class="p-4 sm:p-6 lg:p-8 max-w-2xl space-y-8 font-sans">
    <!-- ── Header ── -->
    <header class="animate-fade-up">
      <RouterLink to="/admin/users">
        <Button variant="ghost" size="sm" class="mb-4 -ml-2 text-muted-foreground hover:text-foreground">
          <ArrowLeft class="h-4 w-4 mr-1" aria-hidden="true" />
          Users
        </Button>
      </RouterLink>
      <h1 class="text-2xl font-semibold text-foreground tracking-tight">New user</h1>
      <p class="text-sm text-muted-foreground mt-1">Create a new user account.</p>
    </header>

    <!-- ── CARD 1: Personal Information ── -->
    <section
      aria-labelledby="personal-info-heading"
      class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-1 transition-shadow hover:shadow-md"
    >
      <h2 id="personal-info-heading" class="text-base font-semibold text-foreground">Personal information</h2>
      <div class="h-px bg-border my-4" />

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <!-- First Name -->
        <div class="space-y-1.5">
          <Label for="firstName" class="text-sm font-medium text-foreground">
            First name <span class="text-destructive">*</span>
          </Label>
          <Input
            id="firstName"
            v-model="firstName"
            type="text"
            autocomplete="given-name"
            placeholder="Jane"
            class="h-9"
          />
        </div>

        <!-- Last Name -->
        <div class="space-y-1.5">
          <Label for="lastName" class="text-sm font-medium text-foreground">
            Last name <span class="text-destructive">*</span>
          </Label>
          <Input
            id="lastName"
            v-model="lastName"
            type="text"
            autocomplete="family-name"
            placeholder="Doe"
            class="h-9"
          />
        </div>

        <!-- Email (full width) -->
        <div class="md:col-span-2 space-y-1.5">
          <Label for="email" class="text-sm font-medium text-foreground">
            Email <span class="text-destructive">*</span>
          </Label>
          <Input
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            placeholder="jane@example.com"
            class="h-9"
          />
        </div>

        <!-- Username -->
        <div class="md:col-span-2 space-y-1.5">
          <Label for="username" class="text-sm font-medium text-foreground">Username</Label>
          <Input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="janedoe"
            class="h-9"
          />
          <p class="text-xs text-muted-foreground">Optional. Used for login if provided.</p>
        </div>
      </div>
    </section>

    <!-- ── CARD 2: Role & Permissions ── -->
    <section
      aria-labelledby="role-heading"
      class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-2 transition-shadow hover:shadow-md"
    >
      <h2 id="role-heading" class="text-base font-semibold text-foreground">Role & permissions</h2>
      <div class="h-px bg-border my-4" />

      <div class="space-y-4">
        <div class="space-y-1.5">
          <Label class="text-sm font-medium text-foreground">
            Role <span class="text-destructive">*</span>
          </Label>
          <Select v-model="role">
            <SelectTrigger class="h-9 w-full">
              <SelectValue placeholder="Select a role" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="r in availableRoles" :key="r.value" :value="r.value">
                {{ r.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <!-- Role descriptions -->
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <div
            v-for="r in availableRoles"
            :key="r.value"
            class="border rounded-lg p-3 transition-colors"
            :class="role === r.value
              ? 'border-primary/50 bg-primary/5'
              : 'border-border bg-transparent'"
          >
            <div class="flex items-center gap-2 mb-1">
              <div
                class="h-4 w-4 rounded-full border-2 flex items-center justify-center"
                :class="role === r.value ? 'border-primary bg-primary' : 'border-border'"
              >
                <Check v-if="role === r.value" class="h-2.5 w-2.5 text-primary-foreground" />
              </div>
              <span class="text-sm font-medium text-foreground">{{ r.label }}</span>
            </div>
            <p class="text-xs text-muted-foreground ml-6">{{ r.description }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CARD 3: Password ── -->
    <section
      aria-labelledby="password-heading"
      class="bg-card border border-border rounded-xl shadow-sm p-6 animate-fade-up stagger-3 transition-shadow hover:shadow-md"
    >
      <h2 id="password-heading" class="text-base font-semibold text-foreground">Password</h2>
      <div class="h-px bg-border my-4" />

      <div class="space-y-4">
        <!-- Password -->
        <div class="space-y-1.5">
          <Label for="password" class="text-sm font-medium text-foreground">
            Password <span class="text-destructive">*</span>
          </Label>
          <div class="relative">
            <Input
              id="password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="••••••••"
              class="h-9 pr-10"
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              class="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              @click="showPassword = !showPassword"
            >
              <Eye v-if="!showPassword" class="h-4 w-4" />
              <EyeOff v-else class="h-4 w-4" />
              <span class="sr-only">Toggle password visibility</span>
            </Button>
          </div>
          <!-- Strength bar -->
          <div v-if="password" class="flex gap-1 mt-2">
            <div
              v-for="i in 4"
              :key="i"
              class="h-1 flex-1 rounded-full transition-colors"
              :class="strengthSegmentColor(i - 1)"
            />
          </div>
          <p v-if="password" class="text-xs text-muted-foreground">{{ strengthLabel }}</p>
        </div>

        <!-- Confirm password -->
        <div class="space-y-1.5">
          <Label for="confirmPassword" class="text-sm font-medium text-foreground">
            Confirm password <span class="text-destructive">*</span>
          </Label>
          <div class="relative">
            <Input
              id="confirmPassword"
              v-model="confirmPassword"
              :type="showConfirm ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="••••••••"
              class="h-9 pr-10"
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              class="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              @click="showConfirm = !showConfirm"
            >
              <Eye v-if="!showConfirm" class="h-4 w-4" />
              <EyeOff v-else class="h-4 w-4" />
              <span class="sr-only">Toggle confirm password visibility</span>
            </Button>
          </div>
          <p
            v-if="confirmPassword && !passwordsMatch"
            class="text-xs text-destructive"
          >
            Passwords do not match.
          </p>
          <p
            v-if="confirmPassword && passwordsMatch"
            class="text-xs text-green-600 dark:text-green-400 flex items-center gap-1"
          >
            <Check class="h-3 w-3" /> Passwords match
          </p>
        </div>

        <!-- Send invite -->
        <div class="flex items-center gap-2 pt-2">
          <Checkbox id="sendInvite" :checked="sendInvite" @update:checked="sendInvite = $event" />
          <Label for="sendInvite" class="text-sm text-foreground cursor-pointer">
            Send welcome email with login instructions
          </Label>
        </div>
      </div>
    </section>

    <!-- ── Submit ── -->
    <div class="mt-2 animate-fade-up stagger-4">
      <Button
        type="button"
        variant="default"
        class="w-full h-10 font-medium"
        :disabled="isLoading || !isFormValid"
        @click="handleSubmit"
      >
        <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
        {{ isLoading ? 'Creating user…' : 'Create user' }}
      </Button>
      <p class="text-xs text-muted-foreground text-center mt-3">
        You can edit this user's profile after creation.
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
