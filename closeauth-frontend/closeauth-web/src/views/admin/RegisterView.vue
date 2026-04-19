<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { AlertCircle, Check, Eye, EyeOff, Loader2, Mail } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const router = useRouter()

// ── State ─────────────────────────────────────────────────────────────────────
const step = ref<'register' | 'otp'>('register')

const firstName = ref('')
const lastName = ref('')
const email = ref('')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const showConfirm = ref(false)
const isLoading = ref(false)
const errorMessage = ref('')

const otpDigits = ref<string[]>(Array(6).fill(''))
const otpRefs = ref<HTMLInputElement[]>([])

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

// ── Computed ───────────────────────────────────────────────────────────────────
const passwordsMatch = computed(
  () => password.value.length > 0 && password.value === confirmPassword.value,
)

// ── Handlers ───────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  errorMessage.value = ''
  isLoading.value = true
  try {
    const response = await fetch('/api/admin/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        firstName: firstName.value,
        lastName: lastName.value,
        email: email.value,
        username: username.value,
        password: password.value,
      }),
    })
    const json = await response.json().catch(() => null)
    if (!response.ok) {
      errorMessage.value = (json?.error as string) || 'Registration failed. Please try again.'
      return
    }
    step.value = 'otp'
  } catch {
    errorMessage.value = 'Registration failed. Please try again.'
  } finally {
    isLoading.value = false
  }
}

const handleVerifyOtp = async () => {
  errorMessage.value = ''
  isLoading.value = true
  try {
    const response = await fetch('/api/admin/register/verify-otp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value, otp: otpDigits.value.join('') }),
    })
    const json = await response.json().catch(() => null)
    if (!response.ok) {
      errorMessage.value = (json?.error as string) || 'Invalid code. Please try again.'
      return
    }
    await router.push('/admin/login')
  } catch {
    errorMessage.value = 'Verification failed. Please try again.'
  } finally {
    isLoading.value = false
  }
}

const handleResendOtp = async () => {
  try {
    await fetch('/api/admin/register/resend-otp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value }),
    })
  } catch {
    // silently fail
  }
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
      <div class="space-y-1.5">
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
      >
        <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
        <p class="text-sm text-destructive">{{ errorMessage }}</p>
      </div>

      <!-- Form -->
      <form class="space-y-5" @submit.prevent="handleSubmit">
        <!-- First / Last name row -->
        <div class="grid grid-cols-2 gap-3 py-2">
          <div class="space-y-2">
            <Label for="firstName" class="text-sm font-medium leading-none py-2">First name</Label>
            <Input
              id="firstName"
              v-model="firstName"
              type="text"
              autocomplete="given-name"
              placeholder="Jane"
              class="h-[36px]"
            />
          </div>
          <div class="space-y-2">
            <Label for="lastName" class="text-sm font-medium leading-none py-2">Last name</Label>
            <Input
              id="lastName"
              v-model="lastName"
              type="text"
              autocomplete="family-name"
              placeholder="Doe"
              class="h-[36px]"
            />
          </div>
        </div>

        <!-- Email -->
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

        <!-- Username -->
        <div class="space-y-2 py-2">
          <Label for="username" class="text-sm font-medium leading-none py-2">Username</Label>
          <Input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="janedoe"
            class="h-[36px]"
          />
          <p class="text-xs text-muted-foreground mt-1">Optional — used for login</p>
        </div>

        <!-- Password + strength -->
        <div class="space-y-2 py-2">
          <Label for="password" class="text-sm font-medium leading-none py-2">Password</Label>
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
          class="w-full h-[36px] transition-all active:scale-[0.98]"
          :disabled="isLoading"
        >
          <Loader2 v-if="isLoading" class="mr-2 h-4 w-4 animate-spin" />
          {{ isLoading ? 'Creating account...' : 'Create account' }}
        </Button>
      </form>
    </template>

    <!-- ── OTP STATE ── -->
    <template v-else>
      <div class="flex flex-col items-center space-y-4 text-center">
        <!-- Icon -->
        <Mail class="h-10 w-10 text-muted-foreground" />

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
        >
          <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
          <p class="text-sm text-destructive">{{ errorMessage }}</p>
        </div>

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
