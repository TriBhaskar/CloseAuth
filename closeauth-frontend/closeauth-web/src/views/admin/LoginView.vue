<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AlertCircle, Eye, EyeOff, Info, Loader2 } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'
import { useAuthStore } from '@/stores/auth'

const route  = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const { isLoading, errorMessage, execute } = useAsyncState()

const username    = ref<string>('')
const password    = ref<string>('')
const showPassword = ref<boolean>(false)
const oauthFlow   = ref<boolean>(false)

onMounted(() => {
  if (route.query.oauth !== undefined) oauthFlow.value = true
})

// ── Dev mock credentials ───────────────────────────────────────────────────────
// Remove this block once the real backend login endpoint is wired up.
const MOCK_USER     = 'admin@closeauth.dev'
const MOCK_PASSWORD = 'admin'

const handleSubmit = async () => {
  // DEV BYPASS: accept mock credentials without hitting the API
  if (username.value === MOCK_USER && password.value === MOCK_PASSWORD) {
    authStore.setUser(MOCK_USER, 'admin', 'Admin')
    await router.push('/admin/dashboard')
    return
  }

  const result = await execute(() =>
    adminService.login({ username: username.value, password: password.value }),
  )
  if (!result) return
  await authStore.fetchMe()
  await router.push('/admin/dashboard')
}
</script>

<template>
  <AuthLayout>
    <!-- Header Section -->
    <div class="space-y-1.5 text-center">
      <h1 class="text-2xl font-semibold tracking-tight">Welcome back</h1>
      <p class="text-sm text-muted-foreground">Login to your account to continue.</p>
    </div>

    <!-- Error/OAuth Note -->
    <div
      v-if="oauthFlow"
      class="flex items-start gap-2 rounded-md border border-blue-200 bg-blue-50 px-3.5 py-3"
    >
      <Info class="mt-0.5 h-3.5 w-3.5 shrink-0 text-blue-500" />
      <p class="text-sm text-blue-700">Complete your authorization request by signing in.</p>
    </div>

    <!-- Main Form -->
    <form class="space-y-6" @submit.prevent="handleSubmit">
      <!-- Email / Username Field -->
      <div class="space-y-2 py-2">
        <Label for="username" class="text-sm font-medium leading-none py-2">Email</Label>
        <Input
          id="username"
          v-model="username"
          type="email"
          autocomplete="username"
          placeholder="m@example.com"
          class="h-[36px]"
        />
      </div>

      <!-- Password Field -->
      <div class="space-y-2 pb-3">
        <div class="flex items-center justify-between">
          <Label for="password" class="text-sm font-medium leading-none py-2">Password</Label>
          <RouterLink
            to="/admin/forgot-password"
            class="text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            Forgot password?
          </RouterLink>
        </div>
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

      <div
        v-if="errorMessage"
        class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3"
      >
        <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
        <p class="text-sm text-destructive">{{ errorMessage }}</p>
      </div>

      <!-- Primary Action -->
      <Button
        type="submit"
        variant="default"
        class="w-full h-[36px] transition-all active:scale-[0.98]"
        :disabled="isLoading"
      >
        <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
        {{ isLoading ? 'Signing in...' : 'Sign In' }}
      </Button>
    </form>

    <!-- Social Divider -->
    <div class="relative">
      <div class="absolute inset-0 flex items-center">
        <span class="w-full border-t border-border"></span>
      </div>
      <div class="relative flex justify-center text-xs uppercase tracking-wider">
        <span class="bg-card px-2 text-muted-foreground font-mono">Or continue with</span>
      </div>
    </div>

    <!-- Social Buttons Grid -->
    <div class="grid grid-cols-2 gap-4">
      <Button variant="outline" type="button" class="h-[36px] items-center justify-center gap-2 transition-all active:scale-[0.98]">
        <svg aria-hidden="true" class="h-4 w-4" data-icon="google" data-prefix="fab" focusable="false" role="img" viewbox="0 0 488 512" xmlns="http://www.w3.org/2000/svg">
          <path d="M488 261.8C488 403.3 391.1 504 248 504 110.8 504 0 393.2 0 256S110.8 8 248 8c66.8 0 123 24.5 166.3 64.9l-67.5 64.9C258.5 52.6 94.3 116.6 94.3 256c0 86.5 69.1 156.6 153.7 156.6 98.2 0 135-70.4 140.8-106.9H248v-85.3h236.1c2.3 12.7 3.9 24.9 3.9 41.4z" fill="currentColor"></path>
        </svg>
        Google
      </Button>
      <Button variant="outline" type="button" class="h-[36px] items-center justify-center gap-2 transition-all active:scale-[0.98]">
        <svg aria-hidden="true" class="h-4 w-4" data-icon="github" data-prefix="fab" focusable="false" role="img" viewbox="0 0 496 512" xmlns="http://www.w3.org/2000/svg">
          <path d="M165.9 397.4c0 2-2.3 3.6-5.2 3.6-3.3.3-5.6-1.3-5.6-3.6 0-2 2.3-3.6 5.2-3.6 3-.3 5.6 1.3 5.6 3.6zm-31.1-4.5c-.7 2 1.3 4.3 4.3 4.9 2.6 1 5.6 0 6.2-2s-1.3-4.3-4.3-5.2c-2.6-.7-5.5.3-6.2 2.3zm44.2-1.7c-2.9.7-4.9 2.6-4.6 4.9.3 2 2.9 3.3 5.9 2.6 2.9-.7 4.9-2.6 4.6-4.6-.3-1.9-3-3.2-5.9-2.9zM244.8 8C106.1 8 0 113.3 0 252c0 110.9 69.8 205.8 169.5 239.2 12.8 2.3 17.3-5.6 17.3-12.1 0-6.2-.3-40.4-.3-61.4 0 0-70 15-84.7-29.8 0 0-11.4-29.1-27.8-36.6 0 0-22.9-15.7 1.6-15.4 0 0 24.9 2 38.6 25.8 21.9 38.6 58.6 27.5 72.9 20.9 2.3-16 8.8-27.1 16-33.7-55.9-6.2-112.3-14.3-112.3-110.5 0-27.5 7.6-41.3 23.6-58.9-2.6-6.5-11.1-33.3 2.6-67.9 20.9-6.5 69 27 69 27 20-5.6 41.5-8.5 62.8-8.5s42.8 2.9 62.8 8.5c0 0 48.1-33.6 69-27 13.7 34.7 5.2 61.4 2.6 67.9 16 17.7 25.8 31.5 25.8 58.9 0 96.5-58.9 104.2-114.8 110.5 9.2 7.9 17 22.9 17 46.4 0 33.7-.3 75.4-.3 83.6 0 6.5 4.6 14.4 17.3 12.1C428.2 457.8 496 362.9 496 252 496 113.3 383.5 8 244.8 8zM97.2 352.9c-1.3 1-1 3.3.7 5.2 1.6 1.6 4.1 2.3 5.2 1 1.3-1 1-3.3-.7-5.2-1.6-1.6-3.9-2.3-5.2-1zm-10.8-8.1c-.7 1.3.3 2.9 2.3 3.9 1.6 1 3.6.7 4.3-.7.7-1.3-.3-2.9-2.3-3.9-2-.6-3.6-.3-4.3.7zm32.4 35.6c-1.6 1.3-1 4.3 1.3 6.2 2.3 2.3 5.2 2.6 6.5 1 1.3-1.3.7-4.3-1.3-6.2-2.2-2.3-5.2-2.6-6.5-1zm-11.4-14.7c-1.6 1-1.6 3.6 0 5.9 1.6 2.3 4.3 3.3 5.6 2.3 1.6-1.3 1.6-3.9 0-6.2-1.4-2.3-4-3.3-5.6-2z" fill="currentColor"></path>
        </svg>
        GitHub
      </Button>
    </div>

    <template #footer>
      <p class="text-sm text-muted-foreground w-full text-center">
        Don't have an account?
        <RouterLink
          to="/admin/register"
          class="font-medium text-foreground hover:underline underline-offset-4 decoration-border"
        >
          Sign up
        </RouterLink>
      </p>
    </template>
  </AuthLayout>
</template>
