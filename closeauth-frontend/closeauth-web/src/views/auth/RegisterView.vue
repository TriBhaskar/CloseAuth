<script setup lang="ts">
// Stage UI-2b, Deliverable 4: the ONE generic registration form for all four
// registration modes. The backend exposes no public way to learn a tenant's
// mode in advance (GET/PUT /v1/tenants/{tenantId}/registration-config is
// authenticated, tenant-admin-only — see the stage prompt's governing
// constraint), so this form never pre-branches on mode: it always renders
// the same fields and decides what to show next purely from POST /register's
// response (submitRegistration's RegisterOutcome, src/api/authRegistration.ts).
//
// Invite acceptance is NOT a separate page/mode — reaching this view via a
// `?invite=` link (the exact shape the backend emails, confirmed against
// InviteService.inviteUrl: "{bffBaseUrl}/register?invite=<token>") just
// reframes this same form's copy and silently carries the token through as
// the `invite_token` field. inviteToken is read ONCE at mount into a plain
// const, deliberately never bound to an <input> anywhere in the template —
// there is no code path that renders it as editable.
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { useOAuthTheme } from '@/composables/useOAuthTheme'
import { submitRegistration } from '@/api/authRegistration'

const route = useRoute()
const router = useRouter()

const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

// Read once at mount — see file header comment. Never reactive, never
// rendered in the template as an input's v-model target.
const inviteToken = typeof route.query.invite === 'string' ? route.query.invite : ''
const isInviteFlow = inviteToken !== ''

const { branding, hasLogo } = useOAuthTheme(clientId.value)
const companyLabel = computed(() => branding.value.companyName || 'CloseAuth')

const form = reactive({
  email: '',
  password: '',
  confirmPassword: '',
  firstName: '',
  lastName: '',
  phone: '',
})

// Keyed by RegisterUserCommand's own property names (email, password,
// firstName, lastName, phone) — same identifiers as `form` above, plus the
// purely client-side `confirmPassword` check. See authRegistration.ts for
// why no separate field-name mapping table is needed.
const fieldErrors = reactive<Record<string, string>>({})
const bannerMessage = ref('')
const isSubmitting = ref(false)

type Phase = 'form' | 'active' | 'pendingApproval'
const phase = ref<Phase>('form')

function clearErrors(): void {
  bannerMessage.value = ''
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return
  clearErrors()

  if (form.confirmPassword !== form.password) {
    fieldErrors.confirmPassword = 'Passwords do not match.'
    return
  }

  isSubmitting.value = true
  try {
    const result = await submitRegistration({
      email: form.email,
      password: form.password,
      firstName: form.firstName || undefined,
      lastName: form.lastName || undefined,
      phone: form.phone || undefined,
      clientId: clientId.value || undefined,
      inviteToken: inviteToken || undefined,
    })

    switch (result.kind) {
      case 'active':
        phase.value = 'active'
        break
      case 'pendingVerification':
        // A real SPA route push (not a top-level navigation) — verification
        // is still entirely within this app, unlike login's cross-origin
        // OAuth resume.
        await router.push({
          path: '/verify-email',
          query: {
            email: form.email,
            ...(clientId.value ? { client_id: clientId.value } : {}),
          },
        })
        return
      case 'pendingApproval':
        phase.value = 'pendingApproval'
        break
      case 'emailConflict':
        // Deliberately generic — never explicitly says "in this tenant",
        // consistent with the backend's own enumeration-safety posture.
        fieldErrors.email = 'An account with this email already exists.'
        break
      case 'inviteRequired':
        bannerMessage.value =
          'This registration requires an invitation. Please check your email for an invite link and use it to register.'
        break
      case 'validationErrors':
        Object.assign(fieldErrors, result.errors)
        break
      case 'error':
      default:
        bannerMessage.value = 'Something went wrong. Please try again.'
        break
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <img
          v-if="hasLogo"
          :src="branding.logoUrl"
          :alt="companyLabel"
          class="h-10 w-auto object-contain"
        >
        <h1 class="text-xl font-semibold tracking-tight">
          {{ isInviteFlow ? `Accept your invite to ${companyLabel}` : `Create your ${companyLabel} account` }}
        </h1>
        <p class="text-sm text-muted-foreground">
          {{ isInviteFlow ? 'Set a password to finish accepting your invitation.' : 'Fill in your details to get started.' }}
        </p>
      </div>
    </template>

    <!-- Success: immediate activation -->
    <div v-if="phase === 'active'" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">Your account has been created and is ready to use.</p>
      <RouterLink to="/login">
        <Button class="w-full">Continue to sign in</Button>
      </RouterLink>
    </div>

    <!-- Success: PENDING, no actionable next step (ADMIN_APPROVED) -->
    <div v-else-if="phase === 'pendingApproval'" class="flex flex-col gap-4 text-center">
      <p class="text-sm text-foreground">
        Your account has been created and is awaiting administrator approval. You'll be able to sign in once it's
        approved.
      </p>
    </div>

    <!-- The one generic form -->
    <form v-else class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
      <div class="flex flex-col gap-1.5">
        <Label for="register-email">Email</Label>
        <Input
          id="register-email"
          v-model="form.email"
          type="email"
          autocomplete="email"
          required
          :disabled="isSubmitting"
          :aria-invalid="!!fieldErrors.email"
        />
        <p v-if="fieldErrors.email" role="alert" class="text-sm text-destructive">{{ fieldErrors.email }}</p>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div class="flex flex-col gap-1.5">
          <Label for="register-first-name">First name</Label>
          <Input
            id="register-first-name"
            v-model="form.firstName"
            type="text"
            autocomplete="given-name"
            :disabled="isSubmitting"
            :aria-invalid="!!fieldErrors.firstName"
          />
          <p v-if="fieldErrors.firstName" role="alert" class="text-sm text-destructive">{{ fieldErrors.firstName }}</p>
        </div>
        <div class="flex flex-col gap-1.5">
          <Label for="register-last-name">Last name</Label>
          <Input
            id="register-last-name"
            v-model="form.lastName"
            type="text"
            autocomplete="family-name"
            :disabled="isSubmitting"
            :aria-invalid="!!fieldErrors.lastName"
          />
          <p v-if="fieldErrors.lastName" role="alert" class="text-sm text-destructive">{{ fieldErrors.lastName }}</p>
        </div>
      </div>

      <div class="flex flex-col gap-1.5">
        <Label for="register-phone">Phone (optional)</Label>
        <Input
          id="register-phone"
          v-model="form.phone"
          type="tel"
          autocomplete="tel"
          :disabled="isSubmitting"
          :aria-invalid="!!fieldErrors.phone"
        />
        <p v-if="fieldErrors.phone" role="alert" class="text-sm text-destructive">{{ fieldErrors.phone }}</p>
      </div>

      <div class="flex flex-col gap-1.5">
        <Label for="register-password">Password</Label>
        <Input
          id="register-password"
          v-model="form.password"
          type="password"
          autocomplete="new-password"
          required
          :disabled="isSubmitting"
          :aria-invalid="!!fieldErrors.password"
        />
        <p v-if="fieldErrors.password" role="alert" class="text-sm text-destructive">{{ fieldErrors.password }}</p>
      </div>

      <div class="flex flex-col gap-1.5">
        <Label for="register-confirm-password">Confirm password</Label>
        <Input
          id="register-confirm-password"
          v-model="form.confirmPassword"
          type="password"
          autocomplete="new-password"
          required
          :disabled="isSubmitting"
          :aria-invalid="!!fieldErrors.confirmPassword"
        />
        <p v-if="fieldErrors.confirmPassword" role="alert" class="text-sm text-destructive">
          {{ fieldErrors.confirmPassword }}
        </p>
      </div>

      <p v-if="bannerMessage" role="alert" class="text-sm text-destructive">{{ bannerMessage }}</p>

      <Button type="submit" class="w-full" :disabled="isSubmitting">
        {{ isSubmitting ? 'Creating account…' : isInviteFlow ? 'Accept invite' : 'Create account' }}
      </Button>
    </form>
  </AuthLayout>
</template>
