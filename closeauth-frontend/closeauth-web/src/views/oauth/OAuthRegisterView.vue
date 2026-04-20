<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AlertCircle, Check, Eye, EyeOff, Loader2, Mail } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const route = useRoute()
const router = useRouter()

// ── State ──────────────────────────────────────────────────────────────────────
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
const clientName = ref('the application')
const clientLogoUrl = ref('')
const clientId = ref('')

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
  return ['Too short', 'Weak', 'Fair', 'Good', 'Strong'][passwordStrength.value]
})

const strengthSegmentColor = (index: number) => {
  if (index >= passwordStrength.value) return 'bg-border'
  return ['bg-red-400', 'bg-amber-400', 'bg-green-400', 'bg-green-500'][passwordStrength.value - 1] ?? 'bg-border'
}

const passwordsMatch = computed(
  () => password.value.length > 0 && password.value === confirmPassword.value,
)

// ── On mount: fetch theme/client info ─────────────────────────────────────────
import { onMounted } from 'vue'

onMounted(async () => {
  clientId.value = (route.query.client_id as string) ?? ''
  if (!clientId.value) return
  try {
    const res = await fetch(`/api/oauth/theme?client_id=${encodeURIComponent(clientId.value)}`)
    if (res.ok) {
      const data = await res.json().catch(() => null)
      if (data?.clientName) clientName.value = data.clientName
      if (data?.clientLogoUrl) clientLogoUrl.value = data.clientLogoUrl
      if (data?.themeButton) document.documentElement.style.setProperty('--theme-button', data.themeButton)
      if (data?.themeBackground) document.documentElement.style.setProperty('--theme-background', data.themeBackground)
      if (data?.themeText) document.documentElement.style.setProperty('--theme-text', data.themeText)
    }
  } catch {
    // fallback already set
  }
})

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
const handleSubmit = async () => {
  errorMessage.value = ''
  isLoading.value = true
  try {
    const response = await fetch('/api/oauth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        firstName: firstName.value,
        lastName: lastName.value,
        email: email.value,
        username: username.value,
        password: password.value,
        client_id: clientId.value,
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
    const response = await fetch('/api/oauth/register/verify-otp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email.value,
        otp: otpDigits.value.join(''),
        client_id: clientId.value,
      }),
    })
    const json = await response.json().catch(() => null)
    if (!response.ok) {
      errorMessage.value = (json?.error as string) || 'Invalid code. Please try again.'
      return
    }
    if (json?.redirect_url) {
      window.location.href = json.redirect_url
    } else {
      await router.push({ path: '/oauth/login', query: { client_id: clientId.value } })
    }
  } catch {
    errorMessage.value = 'Verification failed. Please try again.'
  } finally {
    isLoading.value = false
  }
}

const handleResendOtp = async () => {
  try {
    await fetch('/api/oauth/register/resend-otp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value, client_id: clientId.value }),
    })
  } catch {
    // silently fail
  }
}
</script>

<template>
  <!-- ── Register content (always rendered) ── -->
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

    <!-- 2. Heading -->
    <div class="text-center space-y-1">
      <h1 class="text-xl font-semibold text-foreground">Create your account</h1>
      <p class="text-sm text-muted-foreground">
        Already have an account?
        <RouterLink
          :to="{ path: '/oauth/login', query: { client_id: clientId } }"
          class="underline underline-offset-4 hover:opacity-80 transition-opacity text-primary"
          style="color: var(--theme-button)"
        >
          Sign in here
        </RouterLink>
      </p>
    </div>

    <!-- 3. Error banner -->
    <div
      v-if="errorMessage && step === 'register'"
      class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3"
    >
      <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
      <p class="text-sm text-destructive">{{ errorMessage }}</p>
    </div>

    <!-- 4. Form -->
    <form class="flex flex-col gap-5" @submit.prevent="handleSubmit">
      <!-- First / Last name row -->
      <div class="grid grid-cols-2 gap-3">
        <div class="flex flex-col gap-2">
          <Label for="firstName" class="text-sm font-medium text-foreground">First name</Label>
          <Input
            id="firstName"
            v-model="firstName"
            type="text"
            autocomplete="given-name"
            placeholder="Jane"
            class="h-[36px]"
          />
        </div>
        <div class="flex flex-col gap-2">
          <Label for="lastName" class="text-sm font-medium text-foreground">Last name</Label>
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
      <div class="flex flex-col gap-2">
        <Label for="email" class="text-sm font-medium text-foreground">Email address</Label>
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
      <div class="flex flex-col gap-2">
        <Label for="username" class="text-sm font-medium text-foreground">Username</Label>
        <Input
          id="username"
          v-model="username"
          type="text"
          autocomplete="username"
          placeholder="janedoe"
          class="h-[36px]"
        />
        <p class="text-xs text-muted-foreground">Optional — used for login</p>
      </div>

      <!-- Password + strength -->
      <div class="flex flex-col gap-2">
        <Label for="password" class="text-sm font-medium text-foreground">Password</Label>
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
        <div v-if="password" class="flex gap-1">
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
      <div class="flex flex-col gap-2">
        <Label for="confirmPassword" class="text-sm font-medium text-foreground">
          Confirm password
        </Label>
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
            <span class="sr-only">Toggle confirm password visibility</span>
          </Button>
        </div>
      </div>

      <!-- 5. Submit -->
      <button
        type="submit"
        class="w-full h-9 rounded-md font-medium text-sm bg-primary text-primary-foreground transition-all active:scale-[0.98] disabled:opacity-60 disabled:cursor-not-allowed hover:opacity-90 mt-1"
        style="background-color: var(--theme-button); color: var(--theme-button-foreground, var(--primary-foreground))"
        :disabled="isLoading"
      >
        <span class="flex items-center justify-center gap-2">
          <Loader2 v-if="isLoading" class="h-4 w-4 animate-spin" />
          {{ isLoading ? 'Creating account…' : 'Create account' }}
        </span>
      </button>
    </form>
  </div>

  <!-- ── OTP overlay modal ── -->
  <Teleport to="body">
    <div
      v-if="step === 'otp'"
      class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
    >
      <div class="bg-card rounded-xl border border-border shadow-lg p-8 max-w-sm w-full text-center space-y-4">
        <!-- Icon circle -->
        <div
          class="h-12 w-12 rounded-full mx-auto flex items-center justify-center mb-2"
          style="background-color: color-mix(in oklch, var(--theme-button) 15%, transparent)"
        >
          <Mail
            class="h-5 w-5 text-primary"
            style="color: var(--theme-button)"
          />
        </div>

        <!-- Heading -->
        <div class="space-y-1">
          <h2 class="text-lg font-semibold text-foreground">Verify your email</h2>
          <p class="text-sm text-muted-foreground">
            Enter the code sent to
            <span class="font-medium text-foreground">{{ email }}</span>
          </p>
        </div>

        <!-- OTP error banner -->
        <div
          v-if="errorMessage"
          class="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3.5 py-3 text-left"
        >
          <AlertCircle class="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" />
          <p class="text-sm text-destructive">{{ errorMessage }}</p>
        </div>

        <!-- 6-box OTP input -->
        <div class="flex gap-2 justify-center">
          <input
            v-for="(_, i) in otpDigits"
            :key="i"
            :ref="(el) => { if (el) otpRefs[i] = el as HTMLInputElement }"
            v-model="otpDigits[i]"
            type="text"
            inputmode="numeric"
            maxlength="1"
            class="h-11 w-11 text-center text-lg font-medium border border-border rounded-md bg-background text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors"
            @input="onOtpInput(i, $event)"
            @keydown="onOtpKeydown(i, $event)"
          />
        </div>

        <!-- Verify button -->
        <button
          type="button"
          class="w-full h-9 rounded-md font-medium text-sm bg-primary text-primary-foreground transition-all active:scale-[0.98] disabled:opacity-60 disabled:cursor-not-allowed hover:opacity-90"
          style="background-color: var(--theme-button); color: var(--theme-button-foreground, var(--primary-foreground))"
          :disabled="isLoading || otpDigits.join('').length < 6"
          @click="handleVerifyOtp"
        >
          <span class="flex items-center justify-center gap-2">
            <Loader2 v-if="isLoading" class="h-4 w-4 animate-spin" />
            {{ isLoading ? 'Verifying…' : 'Verify email' }}
          </span>
        </button>

        <!-- Resend -->
        <button
          type="button"
          class="text-sm underline underline-offset-4 hover:opacity-80 transition-opacity text-primary"
          style="color: var(--theme-button)"
          @click="handleResendOtp"
        >
          Resend code
        </button>
      </div>
    </div>
  </Teleport>
</template>
