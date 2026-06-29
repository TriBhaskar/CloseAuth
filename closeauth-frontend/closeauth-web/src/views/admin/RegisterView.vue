<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { AlertCircle, Check, Eye, EyeOff, Loader2, Mail } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'

const router = useRouter()
const { isLoading, errorMessage, execute } = useAsyncState()

// ── State ─────────────────────────────────────────────────────────────────────
const step = ref<'register' | 'otp'>('register')

const firstName       = ref('')
const lastName        = ref('')
const email           = ref('')
const username        = ref('')
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

// ── Handlers ───────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  const result = await execute(() =>
    adminService.register({
      firstName: firstName.value,
      lastName:  lastName.value,
      email:     email.value,
      username:  username.value,
      password:  password.value,
    }),
  )
  if (result) step.value = 'otp'
}

const handleVerifyOtp = async () => {
  const result = await execute(() =>
    adminService.verifyOtp({ email: email.value, verificationCode: otpDigits.value.join('') }),
  )
  if (result) await router.push('/admin/login')
}

const handleResendOtp = async () => {
  await execute(() => adminService.resendOtp({ email: email.value }))
}

// ── OTP input helpers ──────────────────────────────────────────────────────────
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
</script>

<template>
  <AuthLayout>
    <!-- ── REGISTER STATE ── -->
    <template v-if="step === 'register'">
      <!-- Heading -->
      <div class="space-y-1">
        <h1 class="text-xl font-semibold tracking-tight text-center">Create an account</h1>
        <p class="text-sm text-muted-foreground text-center">
          Already have an account?
          <RouterLink
            to="/admin/login"
            class="font-medium text-foreground hover:underline underline-offset-4"
          >
            Sign in
          </RouterLink>
        </p>
      </div>

      <!-- Error banner -->
      <div
        v-if="errorMessage"
        class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3"
        role="alert"
        aria-live="polite"
      >
        <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" aria-hidden="true" />
        <p class="text-sm text-destructive">{{ errorMessage }}</p>
      </div>

      <!-- Form -->
      <form class="space-y-3" @submit.prevent="handleSubmit">
        <!-- First / Last name row -->
        <div class="grid grid-cols-2 gap-3">
          <div class="space-y-1.5">
            <Label for="firstName" class="text-sm font-medium leading-none">First name</Label>
            <Input
              id="firstName"
              v-model="firstName"
              type="text"
              autocomplete="given-name"
              placeholder="Jane"
              class="h-9"
            />
          </div>
          <div class="space-y-1.5">
            <Label for="lastName" class="text-sm font-medium leading-none">Last name</Label>
            <Input
              id="lastName"
              v-model="lastName"
              type="text"
              autocomplete="family-name"
              placeholder="Doe"
              class="h-9"
            />
          </div>
        </div>

        <!-- Email + Username row -->
        <div class="grid grid-cols-2 gap-3">
          <div class="space-y-1.5">
            <Label for="email" class="text-sm font-medium leading-none">Email</Label>
            <Input
              id="email"
              v-model="email"
              type="email"
              autocomplete="email"
              placeholder="m@example.com"
              class="h-9"
            />
          </div>
          <div class="space-y-1.5">
            <Label for="username" class="text-sm font-medium leading-none">Username</Label>
            <Input
              id="username"
              v-model="username"
              type="text"
              autocomplete="username"
              placeholder="janedoe"
              class="h-9"
            />
          </div>
        </div>

        <!-- Password + strength -->
        <div class="space-y-1.5">
          <Label for="password" class="text-sm font-medium leading-none">Password</Label>
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
          <div v-if="password" class="flex gap-1 mt-1.5">
            <div
              v-for="i in 4"
              :key="i"
              class="h-1 flex-1 rounded-full transition-colors duration-300"
              :class="strengthSegmentColor(i - 1)"
            />
          </div>
          <p v-if="password" class="text-[11px] text-muted-foreground">{{ strengthLabel }}</p>
        </div>

        <!-- Confirm password -->
        <div class="space-y-1.5">
          <Label for="confirmPassword" class="text-sm font-medium leading-none"
            >Confirm password</Label
          >
          <div class="relative">
            <Input
              id="confirmPassword"
              v-model="confirmPassword"
              :type="showConfirm ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="••••••••"
              class="h-9 pr-10"
            />
            <!-- Show check when matching, toggle when not -->
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

        <!-- Submit -->
        <Button
          type="submit"
          variant="default"
          class="w-full h-9 mt-1 transition-all active:scale-[0.98]"
          :disabled="isLoading"
          :aria-busy="isLoading"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
          {{ isLoading ? 'Creating account...' : 'Create account' }}
        </Button>
      </form>
    </template>

    <!-- ── OTP STATE ── -->
    <template v-else>
      <div class="flex flex-col items-center space-y-4 text-center" role="status" aria-live="polite">
        <!-- Icon -->
        <Mail class="h-10 w-10 text-muted-foreground" aria-hidden="true" />

        <!-- Heading -->
        <div class="space-y-1.5">
          <h1 class="text-xl font-semibold tracking-tight">Check your email</h1>
          <p class="text-sm text-muted-foreground">
            Enter the 6-digit code sent to
            <span class="font-medium text-foreground">{{ email }}</span>
          </p>
        </div>

        <!-- Error banner -->
        <div
          v-if="errorMessage"
          class="w-full flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3 text-left"
          role="alert"
          aria-live="polite"
        >
          <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" aria-hidden="true" />
          <p class="text-sm text-destructive">{{ errorMessage }}</p>
        </div>

        <!-- OTP inputs -->
        <div class="flex gap-2 justify-center" role="group" aria-label="Enter verification code">
          <input
            v-for="(_, i) in otpDigits"
            :key="i"
            :ref="(el) => { if (el) otpRefs[i] = el as HTMLInputElement }"
            v-model="otpDigits[i]"
            type="text"
            inputmode="numeric"
            maxlength="1"
            :aria-label="`Digit ${i + 1} of 6`"
            class="h-11 w-11 text-center text-lg font-medium border border-border rounded-md bg-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors"
            @input="onOtpInput(i, $event)"
            @keydown="onOtpKeydown(i, $event)"
          />
        </div>

        <!-- Verify button -->
        <Button
          type="button"
          variant="default"
          class="w-full h-[36px] transition-all active:scale-[0.98]"
          :disabled="isLoading || otpDigits.join('').length < 6"
          @click="handleVerifyOtp"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
          {{ isLoading ? 'Verifying...' : 'Verify email' }}
        </Button>

        <!-- Resend -->
        <button
          type="button"
          class="text-sm text-muted-foreground underline underline-offset-4 hover:text-foreground transition-colors"
          @click="handleResendOtp"
        >
          Resend code
        </button>
      </div>
    </template>
  </AuthLayout>
</template>
