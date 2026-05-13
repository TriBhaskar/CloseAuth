<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { AlertCircle, Eye, EyeOff, Info, Loader2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { useAsyncState } from '@/composables/useAsyncState'
import { oauthService } from '@/api/services'

// ── Composables ────────────────────────────────────────────────────────────────
const { clientId, clientName, clientLogoUrl, loadTheme } = useOAuthTheme()
const { isLoading, errorMessage, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const usernameOrEmail = ref('')
const password = ref('')
const rememberMe = ref(false)
const showPassword = ref(false)

// ── On mount: fetch theme/client info ─────────────────────────────────────────
onMounted(loadTheme)

// ── Handlers ───────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  const result = await execute(() =>
    oauthService.login({
      username: usernameOrEmail.value,
      password: password.value,
      rememberMe: rememberMe.value,
    }),
  )
  if (result?.redirect_url) {
    window.location.href = result.redirect_url
  }
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- 1. Logo block -->
    <div class="flex justify-center">
      <img
        v-if="clientLogoUrl"
        :src="clientLogoUrl"
        alt="App logo"
        class="h-12 object-contain"
      />
      <div
        v-else
        class="h-12 w-12 rounded-md flex items-center justify-center bg-primary"
        style="background-color: var(--theme-button)"
      >
        <span class="text-primary-foreground text-xl font-semibold">
          {{ clientName.charAt(0).toUpperCase() }}
        </span>
      </div>
    </div>

    <!-- 2. Heading block -->
    <div class="text-center space-y-1">
      <h1 class="text-xl font-semibold text-foreground">Sign in to continue</h1>
      <p class="text-sm text-muted-foreground">Sign in to access {{ clientName }}</p>
    </div>

    <!-- 3. OAuth notice banner -->
    <div class="flex items-start gap-2 bg-blue-50 border border-blue-200 rounded-md px-3.5 py-3">
      <Info class="h-4 w-4 text-blue-500 shrink-0 mt-0.5" />
      <p class="text-sm text-blue-700">Complete your authorization request by signing in.</p>
    </div>

    <!-- 4. Error banner -->
    <div
      v-if="errorMessage"
      class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3"
    >
      <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
      <p class="text-sm text-destructive">{{ errorMessage }}</p>
    </div>

    <!-- 5. Form -->
    <form class="flex flex-col gap-5" @submit.prevent="handleSubmit">
      <!-- Username or Email -->
      <div class="flex flex-col gap-2">
        <Label for="usernameOrEmail" class="text-sm font-medium text-foreground">
          Username or Email
        </Label>
        <Input
          id="usernameOrEmail"
          v-model="usernameOrEmail"
          type="text"
          autocomplete="username"
          placeholder="m@example.com"
          class="h-[36px]"
        />
      </div>

      <!-- Password -->
      <div class="flex flex-col gap-2">
        <Label for="password" class="text-sm font-medium text-foreground">Password</Label>
        <div class="relative">
          <Input
            id="password"
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            placeholder="••••••••"
            class="h-[36px] pr-10"
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
      </div>

      <!-- Remember me + Forgot password -->
      <div class="flex items-center justify-between">
        <label class="flex items-center gap-2 text-sm text-foreground cursor-pointer" for="rememberMe">
          <input
            id="rememberMe"
            v-model="rememberMe"
            type="checkbox"
            class="h-4 w-4 rounded border-border accent-foreground cursor-pointer"
          />
          <span>Remember me</span>
        </label>

        <a
          href="#"
          class="text-sm text-primary underline underline-offset-4 hover:opacity-80 transition-opacity"
          style="color: var(--theme-button)"
        >
          Forgot password?
        </a>
      </div>

      <!-- 6. Submit button -->
      <button
        type="submit"
        class="w-full h-9 rounded-md font-medium text-sm bg-primary text-primary-foreground transition-all active:scale-[0.98] disabled:opacity-60 disabled:cursor-not-allowed hover:opacity-90 mt-1"
        style="background-color: var(--theme-button); color: var(--theme-button-foreground, var(--primary-foreground))"
        :disabled="isLoading"
      >
        <span class="flex items-center justify-center gap-2">
          <Loader2 v-if="isLoading" class="h-4 w-4 animate-spin" />
          {{ isLoading ? 'Signing in…' : 'Sign in' }}
        </span>
      </button>
    </form>

    <!-- 7. Register link -->
    <p class="text-center text-sm text-muted-foreground">
      Don't have an account?
      <RouterLink
        :to="{ path: '/oauth/register', query: { client_id: clientId } }"
        class="text-primary underline underline-offset-4 hover:opacity-80 transition-opacity"
        style="color: var(--theme-button)"
      >
        Sign up
      </RouterLink>
    </p>
  </div>
</template>
