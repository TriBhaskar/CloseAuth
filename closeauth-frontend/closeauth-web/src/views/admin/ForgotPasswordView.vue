<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { AlertCircle, Check, Eye, EyeOff, Loader2 } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAsyncState } from '@/composables/useAsyncState'
import { apiClient } from '@/api/client'

const router = useRouter()
const { isLoading, errorMessage, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const step = ref<1 | 2 | 3>(1)

const email           = ref('')
const password        = ref('')
const confirmPassword = ref('')
const showPassword    = ref(false)
const showConfirm     = ref(false)

const otpDigits = ref<string[]>(Array(6).fill(''))
const otpRefs   = ref<HTMLInputElement[]>([])

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

// ── Step helpers ───────────────────────────────────────────────────────────────
const stepLabels = ['Email', 'Verify', 'Reset'] as const

const circleClass = (s: number) => {
  if (s < step.value) return 'bg-foreground text-background rounded-full'
  if (s === step.value) return 'bg-foreground text-background rounded-full font-medium'
  return 'border border-border text-muted-foreground rounded-full'
}

const labelClass = (s: number) =>
  s === step.value ? 'text-[11px] text-foreground font-medium' : 'text-[11px] text-muted-foreground'

const lineClass = (afterStep: number) =>
  afterStep < step.value ? 'bg-foreground' : 'bg-border'

// ── OTP helpers ────────────────────────────────────────────────────────────────
const onOtpInput = async (index: number, event: Event) => {
  const val = (event.target as HTMLInputElement).value.replace(/\D/g, '')
  otpDigits.value[index] = val.slice(-1)
  ;(event.target as HTMLInputElement).value = otpDigits.value[index]
  if (val && index < 5) {
    await nextTick()
    otpRefs.value[index + 1]?.focus()
  }
}

const onOtpKeydown = async (index: number, event: KeyboardEvent) => {
  if (event.key === 'Backspace' && !otpDigits.value[index] && index > 0) {
    await nextTick()
    otpRefs.value[index - 1]?.focus()
  }
}

// ── Handlers ───────────────────────────────────────────────────────────────────
const handleStep1 = async () => {
  const result = await execute(() =>
    apiClient.post('/admin/forgot-password/request', { email: email.value }),
  )
  if (result !== null) step.value = 2
}

const handleStep2 = async () => {
  const result = await execute(() =>
    apiClient.post('/admin/forgot-password/verify-otp', {
      email: email.value,
      otp: otpDigits.value.join(''),
    }),
  )
  if (result !== null) step.value = 3
}

const handleResendOtp = async () => {
  await execute(() =>
    apiClient.post('/admin/forgot-password/request', { email: email.value }),
  )
}

const handleStep3 = async () => {
  const result = await execute(() =>
    apiClient.post('/admin/forgot-password/reset', {
      email: email.value,
      otp: otpDigits.value.join(''),
      password: password.value,
    }),
  )
  if (result !== null) await router.push('/admin/login')
}
</script>

<template>
  <AuthLayout>
    <!-- ── Step Indicator ── -->
    <div class="flex items-start w-full mb-2">
      <template v-for="(label, i) in stepLabels" :key="label">
        <!-- Step circle + label -->
        <div class="flex flex-col items-center gap-1">
          <div
            class="h-6 w-6 flex items-center justify-center text-xs shrink-0 transition-colors duration-300"
            :class="circleClass(i + 1)"
          >
            <Check v-if="i + 1 < step" class="h-3 w-3" />
            <span v-else>{{ i + 1 }}</span>
          </div>
          <span class="transition-colors duration-300" :class="labelClass(i + 1)">{{ label }}</span>
        </div>

        <!-- Connecting line (between steps) -->
        <div
          v-if="i < stepLabels.length - 1"
          class="h-px flex-1 self-start mt-3 mx-2 transition-colors duration-300"
          :class="lineClass(i + 1)"
        />
      </template>
    </div>

    <!-- ── STEP 1 — Enter Email ── -->
    <template v-if="step === 1">
      <div class="space-y-1.5">
        <h1 class="text-xl font-semibold tracking-tight">Forgot password?</h1>
        <p class="text-sm text-muted-foreground">
          We'll send a verification code to your email.
        </p>
      </div>

      <form class="space-y-5" @submit.prevent="handleStep1">
        <div class="space-y-2 py-2">
          <Label for="email" class="text-sm font-medium leading-none py-2">Email</Label>
          <Input
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            placeholder="m@example.com"
            class="h-[36px]"
          />
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
          :disabled="isLoading || !email"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
          {{ isLoading ? 'Sending...' : 'Send verification code' }}
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

    <!-- ── STEP 2 — Verify Code ── -->
    <template v-else-if="step === 2">
      <div class="space-y-1.5">
        <h1 class="text-xl font-semibold tracking-tight">Check your email</h1>
        <p class="text-sm text-muted-foreground">
          Code sent to
          <span class="font-medium text-foreground">{{ email }}</span>
        </p>
      </div>

      <div class="space-y-5">
        <!-- Back link -->
        <button
          type="button"
          class="text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors"
          @click="step = 1"
        >
          ← Use a different email
        </button>

        <!-- OTP inputs -->
        <div class="flex gap-2 justify-center">
          <input
            v-for="(_, i) in otpDigits"
            :key="i"
            :ref="(el) => { if (el) otpRefs[i] = el as HTMLInputElement }"
            v-model="otpDigits[i]"
            type="text"
            inputmode="numeric"
            maxlength="1"
            class="h-11 w-11 text-center text-lg font-medium border border-border rounded-md bg-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors"
            @input="onOtpInput(i, $event)"
            @keydown="onOtpKeydown(i, $event)"
          />
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
          type="button"
          variant="default"
          class="w-full h-[36px] transition-all active:scale-[0.98]"
          :disabled="isLoading || otpDigits.join('').length < 6"
          @click="handleStep2"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
          {{ isLoading ? 'Verifying...' : 'Verify code' }}
        </Button>

        <p class="text-center">
          <button
            type="button"
            class="text-sm text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors"
            @click="handleResendOtp"
          >
            Resend code
          </button>
        </p>
      </div>
    </template>

    <!-- ── STEP 3 — Reset Password ── -->
    <template v-else>
      <div class="space-y-1.5">
        <h1 class="text-xl font-semibold tracking-tight">Set a new password</h1>
        <p class="text-sm text-muted-foreground">Choose a strong password.</p>
      </div>

      <form class="space-y-5" @submit.prevent="handleStep3">
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
