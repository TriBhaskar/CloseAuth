<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AlertCircle, Check, CheckCircle2, Eye, EyeOff, Loader2, XCircle } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'

const route = useRoute()
const router = useRouter()
const { isLoading, errorMessage, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const token = ref('')
const tokenValid = ref<boolean | null>(null) // null = still checking
const tokenError = ref('')

const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const showConfirm = ref(false)
const resetSuccess = ref(false)

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

// ── Validate token on mount ────────────────────────────────────────────────────
onMounted(async () => {
  const queryToken = route.query.token as string | undefined

  if (!queryToken) {
    tokenValid.value = false
    tokenError.value = 'No reset token provided. Please request a new password reset link.'
    return
  }

  token.value = queryToken

  try {
    const result = await adminService.validateResetToken(queryToken)
    tokenValid.value = result.valid
    if (!result.valid) {
      tokenError.value = result.message || 'This reset link is invalid or has expired.'
    }
  } catch {
    tokenValid.value = false
    tokenError.value = 'This reset link is invalid or has expired.'
  }
})

// ── Submit handler ─────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  const result = await execute(() =>
    adminService.resetPassword({
      token: token.value,
      newPassword: password.value,
      confirmPassword: confirmPassword.value,
    }),
  )

  if (result !== null) {
    resetSuccess.value = true
    // Redirect to login after a brief delay
    setTimeout(() => {
      router.push('/admin/login')
    }, 3000)
  }
}
</script>

<template>
  <AuthLayout>
    <!-- ── Loading: Validating Token ── -->
    <template v-if="tokenValid === null">
      <div class="flex flex-col items-center text-center space-y-4 py-8">
        <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
        <p class="text-sm text-muted-foreground">Validating your reset link…</p>
      </div>
    </template>

    <!-- ── Invalid / Expired Token ── -->
    <template v-else-if="!tokenValid">
      <div class="flex flex-col items-center text-center space-y-4">
        <div
          class="h-12 w-12 flex items-center justify-center rounded-full bg-destructive/10"
        >
          <XCircle class="h-6 w-6 text-destructive" />
        </div>

        <div class="space-y-1.5">
          <h1 class="text-xl font-semibold tracking-tight">Invalid reset link</h1>
          <p class="text-sm text-muted-foreground">
            {{ tokenError }}
          </p>
        </div>

        <div class="flex flex-col gap-2 w-full">
          <RouterLink to="/admin/forgot-password">
            <Button
              variant="default"
              class="w-full h-[36px] transition-all active:scale-[0.98]"
            >
              Request a new reset link
            </Button>
          </RouterLink>

          <RouterLink
            to="/admin/login"
            class="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline underline-offset-4 text-center"
          >
            ← Back to sign in
          </RouterLink>
        </div>
      </div>
    </template>

    <!-- ── Reset Success ── -->
    <template v-else-if="resetSuccess">
      <div class="flex flex-col items-center text-center space-y-4">
        <div
          class="h-12 w-12 flex items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
        >
          <CheckCircle2 class="h-6 w-6 text-green-600 dark:text-green-400" />
        </div>

        <div class="space-y-1.5">
          <h1 class="text-xl font-semibold tracking-tight">Password reset successful</h1>
          <p class="text-sm text-muted-foreground">
            Your password has been updated. Redirecting you to sign in…
          </p>
        </div>

        <RouterLink
          to="/admin/login"
          class="text-sm font-medium text-foreground hover:underline underline-offset-4"
        >
          Sign in now
        </RouterLink>
      </div>
    </template>

    <!-- ── Reset Password Form ── -->
    <template v-else>
      <div class="space-y-1.5">
        <h1 class="text-xl font-semibold tracking-tight">Set a new password</h1>
        <p class="text-sm text-muted-foreground">Choose a strong password for your account.</p>
      </div>

      <form class="space-y-5" @submit.prevent="handleSubmit">
        <!-- New password -->
        <div class="space-y-2 py-2">
          <Label for="password" class="text-sm font-medium leading-none py-2">New password</Label>
          <div class="relative">
            <Input
              id="password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
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
          <!-- Strength bar -->
          <div v-if="password" class="flex gap-1 mt-2">
            <div
              v-for="i in 4"
              :key="i"
              class="h-1 flex-1 rounded-full transition-colors duration-300"
              :class="strengthSegmentColor(i - 1)"
            />
          </div>
          <p v-if="password" class="text-xs text-muted-foreground">{{ strengthLabel }}</p>
        </div>

        <!-- Confirm password -->
        <div class="space-y-2 pb-3">
          <Label for="confirmPassword" class="text-sm font-medium leading-none py-2"
          >Confirm password</Label
          >
          <div class="relative">
            <Input
              id="confirmPassword"
              v-model="confirmPassword"
              :type="showConfirm ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="••••••••"
              class="h-[36px] pr-10"
            />
            <span
              v-if="passwordsMatch"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-green-500"
            >
              <Check class="h-4 w-4" />
            </span>
            <Button
              v-else
              type="button"
              variant="ghost"
              size="icon"
              class="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              @click="showConfirm = !showConfirm"
            >
              <Eye v-if="!showConfirm" class="h-4 w-4" />
              <EyeOff v-else class="h-4 w-4" />
              <span class="sr-only">Toggle password visibility</span>
            </Button>
          </div>
        </div>

        <!-- Error banner -->
        <div
          v-if="errorMessage"
          class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3"
        >
          <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
          <p class="text-sm text-destructive">{{ errorMessage }}</p>
        </div>

        <Button
          type="submit"
          variant="default"
          class="w-full h-[36px] transition-all active:scale-[0.98]"
          :disabled="isLoading || !passwordsMatch"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
          {{ isLoading ? 'Resetting...' : 'Reset password' }}
        </Button>
      </form>

      <p class="text-sm text-muted-foreground text-center">
        Remember it?
        <RouterLink
          to="/admin/login"
          class="font-medium text-foreground hover:underline underline-offset-4"
        >
          Sign in
        </RouterLink>
      </p>
    </template>
  </AuthLayout>
</template>

