<script setup lang="ts">
// Stage UI-2b, Deliverable 4 / FE-2c: the registration form for all four
// registration modes — now mode-aware (FE-2b's `registrationMode`-on-
// `/branding` addition made this possible; this view previously predated
// it and never branched on mode at all).
//
// Route-level gate (spec §6.2.3): resolves branding BEFORE rendering
// anything, mirroring TenantResolverView.vue's (FE-2a) established
// "resolve-before-render" pattern rather than inventing a new one —
// `ADMIN_APPROVED` always 404s this route; `INVITE_ONLY` 404s unless an
// `?invite=` token is present (a PRESENT token can't be pre-validated — no
// unauthenticated invite-preview endpoint exists — so an invite-bearing
// visit always renders the form and lets the existing POST-time 403 handle
// a bad/reused/mismatched token, unchanged from before).
//
// Invite acceptance is NOT a separate page/mode — reaching this view via a
// `?invite=&email=` link (the shape InviteService.inviteUrl emits) reframes
// this same form's copy, pre-fills and LOCKS the email field (spec §6.2.3),
// and silently carries the token through as `invite_token`. The backend's
// own InviteOnlyRegistrationStrategy independently re-validates the
// email-token match at submit regardless, so a tampered `email` query
// param just gets the same generic 403 it always did — never a bypass.
//
// The 409 email-conflict reveal on submit is DELIBERATELY unchanged this
// session (confirmed decision, FE-2c plan): the backend's existing,
// tested platform default stays authoritative; spec's enumeration-safe
// post-submit copy is tracked, not built.
import { computed, onMounted, reactive, ref, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import EntryShell from '@/shells/EntryShell.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import TenantBrandingProvider, {
  type Branding,
} from '@/components/common/TenantBrandingProvider.vue'
import NewPasswordFields from '@/components/common/NewPasswordFields.vue'
import { submitRegistration } from '@/api/authRegistration'
import { fetchBranding } from '@/api/publicBranding'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()
const router = useRouter()

const clientId = computed(() => {
  const raw = route.query.client_id
  return typeof raw === 'string' ? raw : ''
})

const loginPath = computed(() => hostedAuthPath(route, '/login'))

// Cross-origin login continuity discipline (see LoginView.vue's header):
// captured once, verbatim, forwarded whole rather than hand-picked — this
// is what lets /verify-email eventually resume the pending authorization
// request (FE-2d's job to consume; this session's job to not drop it).
const authorizeQuery = window.location.search

// Read once at mount — see file header comment. Never rendered as an
// input's v-model target for the invite TOKEN (still opaque, still
// carried through silently); the invite EMAIL, when present, pre-fills and
// locks the one real input that shows it.
const inviteToken = typeof route.query.invite === 'string' ? route.query.invite : ''
const isInviteFlow = inviteToken !== ''
const inviteEmail = typeof route.query.email === 'string' ? route.query.email : ''
const isEmailLocked = isInviteFlow && inviteEmail !== ''

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

// ---- route-level gate (spec §6.2.3) ----------------------------------
type ResolutionState = 'loading' | 'ready' | 'blocked'
const resolutionState = ref<ResolutionState>('loading')

function isClosedMode(mode: string | null): boolean {
  if (mode === 'ADMIN_APPROVED') return true
  if (mode === 'INVITE_ONLY' && !inviteToken) return true
  return false
}

onMounted(async () => {
  const result = await fetchBranding(clientId.value)
  // A branding-fetch failure (network/error) fails OPEN — a transient hiccup
  // shouldn't 404 a real registration attempt; the form renders with
  // platform-default branding, same as TenantBrandingProvider's own
  // graceful degradation.
  const mode = result.kind === 'ok' ? result.value.registrationMode : null
  if (isClosedMode(mode)) {
    resolutionState.value = 'blocked'
    await router.replace('/not-found')
    return
  }
  resolutionState.value = 'ready'
})

// ---- form ----------------------------------------------------------------
const form = reactive({
  email: inviteEmail,
  firstName: '',
  lastName: '',
  phone: '',
})

// Keyed by RegisterUserCommand's own property names (email, firstName,
// lastName, phone) — password/confirmPassword now live inside
// NewPasswordFields (bare mode), not here.
const fieldErrors = reactive<Record<string, string>>({})
const bannerMessage = ref('')
const isSubmitting = ref(false)

type Phase = 'form' | 'active' | 'pendingApproval'
const phase = ref<Phase>('form')

const passwordFields = useTemplateRef<InstanceType<typeof NewPasswordFields>>('passwordFields')

function clearErrors(): void {
  bannerMessage.value = ''
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return
  clearErrors()

  // NewPasswordFields.validate() already populated its own inline error
  // (mismatch / too short) when it returns null — nothing more to show here.
  const password = passwordFields.value?.validate()
  if (!password) return

  isSubmitting.value = true
  try {
    const result = await submitRegistration({
      email: form.email,
      password,
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
        // OAuth resume. authorize_query carries the original /oauth2/authorize
        // request forward for /verify-email's own eventual auto-continue.
        await router.push({
          path: hostedAuthPath(route, '/verify-email'),
          query: {
            email: form.email,
            ...(clientId.value ? { client_id: clientId.value } : {}),
            ...(authorizeQuery ? { authorize_query: authorizeQuery } : {}),
          },
        })
        return
      case 'pendingApproval':
        phase.value = 'pendingApproval'
        break
      case 'emailConflict':
        // Deliberately generic — never explicitly says "in this tenant",
        // consistent with the backend's own enumeration-safety posture.
        // The 409 reveal itself is a confirmed, deliberate decision this
        // session — see file header comment.
        fieldErrors.email = 'An account with this email already exists.'
        break
      case 'inviteRequired':
        bannerMessage.value =
          'This registration requires an invitation. Please check your email for an invite link and use it to register.'
        break
      case 'validationErrors':
        Object.assign(fieldErrors, result.errors)
        // A server-reported password error has nowhere to render inside
        // NewPasswordFields (it owns its own, client-only fieldErrors) —
        // surface it via the banner rather than silently drop it. Rare in
        // practice: the only real server-side rule (@Size(min=8,max=200))
        // is already client-checked before this request is ever sent.
        if (result.errors.password && !bannerMessage.value) {
          bannerMessage.value = result.errors.password
        }
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
  <template v-if="resolutionState === 'ready'">
    <TenantBrandingProvider :client-id="clientId" v-slot="{ branding, hasLogo }">
      <AuthShell>
        <template #above>
          <div class="flex flex-col items-center gap-2 text-center">
            <!-- FE-6.5: reserved box — see LoginView.vue's identical fix. -->
            <div class="h-10">
              <img
                v-if="hasLogo"
                :src="branding.logoUrl"
                :alt="companyLabel(branding)"
                class="h-10 w-auto object-contain"
              />
            </div>
            <h1 class="text-xl font-semibold tracking-tight">
              {{
                isInviteFlow
                  ? `Accept your invite to ${companyLabel(branding)}`
                  : `Create your ${companyLabel(branding)} account`
              }}
            </h1>
            <p class="text-sm text-muted-foreground">
              {{
                isInviteFlow
                  ? 'Set a password to finish accepting your invitation.'
                  : 'Fill in your details to get started.'
              }}
            </p>
          </div>
        </template>

        <!-- Success: immediate activation -->
        <div v-if="phase === 'active'" class="flex flex-col gap-4 text-center">
          <p class="text-sm text-foreground">Your account has been created and is ready to use.</p>
          <RouterLink :to="loginPath">
            <Button class="w-full">Continue to sign in</Button>
          </RouterLink>
        </div>

        <!-- Success: PENDING, no actionable next step (ADMIN_APPROVED) -->
        <div v-else-if="phase === 'pendingApproval'" class="flex flex-col gap-4 text-center">
          <p class="text-sm text-foreground">
            Your account has been created and is awaiting administrator approval. You'll be able to
            sign in once it's approved.
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
              :readonly="isEmailLocked"
              :disabled="isSubmitting"
              :aria-invalid="!!fieldErrors.email"
            />
            <p v-if="fieldErrors.email" role="alert" class="text-sm text-destructive">
              {{ fieldErrors.email }}
            </p>
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
              <p v-if="fieldErrors.firstName" role="alert" class="text-sm text-destructive">
                {{ fieldErrors.firstName }}
              </p>
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
              <p v-if="fieldErrors.lastName" role="alert" class="text-sm text-destructive">
                {{ fieldErrors.lastName }}
              </p>
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
            <p v-if="fieldErrors.phone" role="alert" class="text-sm text-destructive">
              {{ fieldErrors.phone }}
            </p>
          </div>

          <NewPasswordFields
            ref="passwordFields"
            id-prefix="register"
            bare
            show-checklist
            :min-length="8"
            :is-submitting="isSubmitting"
          />

          <p v-if="bannerMessage" role="alert" class="text-sm text-destructive">
            {{ bannerMessage }}
          </p>

          <Button type="submit" class="w-full" :disabled="isSubmitting">
            {{
              isSubmitting ? 'Creating account…' : isInviteFlow ? 'Accept invite' : 'Create account'
            }}
          </Button>
        </form>
      </AuthShell>
    </TenantBrandingProvider>
  </template>

  <template v-else-if="resolutionState === 'loading'">
    <EntryShell>
      <div class="flex flex-col items-center gap-4 text-center">
        <p class="text-sm text-muted-foreground" aria-live="polite">Loading…</p>
      </div>
    </EntryShell>
  </template>

  <!-- resolutionState === 'blocked': mid-redirect to /not-found, render nothing -->
</template>
