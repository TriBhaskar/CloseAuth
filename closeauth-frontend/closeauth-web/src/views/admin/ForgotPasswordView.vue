<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { AlertCircle, CheckCircle2, Loader2, Mail } from 'lucide-vue-next'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'

const { isLoading, errorMessage, execute } = useAsyncState()

// ── State ──────────────────────────────────────────────────────────────────────
const email = ref('')
const linkSent = ref(false)

// ── Handler ────────────────────────────────────────────────────────────────────
const handleSubmit = async () => {
  const forgotPasswordLink = `${globalThis.location.origin}/admin/reset-password`

  const result = await execute(() =>
    adminService.forgotPassword({
      email: email.value,
      forgotPasswordLink,
    }),
  )

  if (result !== null) {
    linkSent.value = true
  }
}
</script>

<template>
  <AuthLayout>
    <!-- ── Link Sent — Success State ── -->
    <template v-if="linkSent">
      <div class="flex flex-col items-center text-center space-y-4">
        <div
          class="h-12 w-12 flex items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
        >
          <CheckCircle2 class="h-6 w-6 text-green-600 dark:text-green-400" />
        </div>

        <div class="space-y-1.5">
          <h1 class="text-xl font-semibold tracking-tight">Check your email</h1>
          <p class="text-sm text-muted-foreground">
            If an account exists for
            <span class="font-medium text-foreground">{{ email }}</span
            >, we've sent a password reset link. The link expires in 10 minutes.
          </p>
        </div>

        <div class="flex items-start gap-2 rounded-md border border-border bg-muted/50 px-3.5 py-3 w-full">
          <Mail class="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          <p class="text-sm text-muted-foreground text-left">
            Don't see the email? Check your spam folder or
            <button
              type="button"
              class="font-medium text-foreground underline underline-offset-4 hover:text-foreground/80 transition-colors"
              @click="linkSent = false"
            >
              try again
            </button>
            with a different email.
          </p>
        </div>

        <RouterLink
          to="/admin/login"
          class="text-sm font-medium text-foreground hover:underline underline-offset-4"
        >
          ← Back to sign in
        </RouterLink>
      </div>
    </template>

    <!-- ── Email Form ── -->
    <template v-else>
      <div class="space-y-1.5">
        <h1 class="text-xl font-semibold tracking-tight">Forgot password?</h1>
        <p class="text-sm text-muted-foreground">
          Enter your email and we'll send you a link to reset your password.
        </p>
      </div>

      <form class="space-y-5" @submit.prevent="handleSubmit">
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
          {{ isLoading ? 'Sending...' : 'Send reset link' }}
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
