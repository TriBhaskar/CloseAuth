<script setup lang="ts">
// Phase 4a: the landing page for BOTH tenant-onboarding password-rotation
// on-ramps — PasswordRotationService.rotationPageUrl builds this exact URL
// (`{bff.baseUrl}/password-rotation?token=...&client_id=...` plus, only on
// the temp-password on-ramp, `&authorize_query=...`) for both the
// rotation-required login redirect AND the onboarding email link.
//
// There is deliberately no runtime "mode" — see this component's own
// on-ramp handling below: both cases read `redirectTo` off the confirm
// response and navigate there. What differs between the two on-ramps is
// entirely server-computed (LoginSuccessResponder decides the destination
// from whether authorize_query was present), not something this view
// branches on.
//
// # authorize_query — read exactly once, decoded exactly once
//
// window.location.search, not route.query — same idiom as LoginView.vue
// (see its header comment) and for the identical reason: URLSearchParams
// decodes percent-encoding using the same semantics as the Java
// URLEncoder/@RequestParam pair that produced this value, and reading the
// raw querystring rather than vue-router's parsed route.query avoids any
// risk of the router's own parsing re-normalizing a value that contains its
// own structural `&`/`=` characters (percent-encoded once already by
// PasswordRotationService.rotationPageUrl's enc() call — see that class's
// javadoc). This value is forwarded to authPasswordRotation.ts VERBATIM,
// with NO further encoding — encodeURIComponent-ing it again here would
// double-encode `=`/`&` into literal `%3D`/`%26` in the eventual
// /oauth2/authorize resume URL, which SAS would then reject.
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'
import NewPasswordFields from '@/components/common/NewPasswordFields.vue'
import TenantBrandingProvider, {
  type Branding,
} from '@/components/common/TenantBrandingProvider.vue'
import { confirmPasswordRotation } from '@/api/authPasswordRotation'
import { hostedAuthPath } from '@/lib/hostedAuthPath'

const route = useRoute()
const loginPath = hostedAuthPath(route, '/login')

// Read once, at module-eval time for this component instance — never
// router.replace()'d away afterward (that would re-normalize the query and
// risk mangling authorize_query).
const params = new URLSearchParams(window.location.search)
const token = params.get('token') ?? ''
const clientId = params.get('client_id') ?? ''
const authorizeQuery = params.get('authorize_query') ?? ''

// Both required @RequestParam fields on the backend's confirm endpoint —
// submitting without them is a guaranteed 400. Rather than let a first-time
// user discover that by submitting, treat an incomplete link as its own
// state: no form is rendered at all.
const linkIncomplete = !token || !clientId

function companyLabel(branding: Branding): string {
  return branding.companyName || 'CloseAuth'
}

const bannerMessage = ref('')
const isSubmitting = ref(false)
// Distinct from the generic error banner: the password WAS already changed
// server-side in this case (the confirm call's 200 IS the success signal),
// so the copy must not read as a failure — see authPasswordRotation.ts's
// 'missingRedirect' doc comment.
const succeededWithoutRedirect = ref(false)

async function handleSubmit(password: string): Promise<void> {
  if (isSubmitting.value) return
  bannerMessage.value = ''
  succeededWithoutRedirect.value = false
  isSubmitting.value = true

  // confirmPasswordRotation never throws — every failure mode (network,
  // non-2xx, malformed body) is caught internally and returned as a typed
  // result — so no try/finally is needed here.
  const result = await confirmPasswordRotation({
    token,
    password,
    clientId,
    authorizeQuery: authorizeQuery || undefined,
  })

  if (result.ok) {
    // Real top-level navigation — never router.push. This crosses to the
    // backend's own origin (/oauth2/authorize resume) or, on the
    // emailed-link on-ramp, lands on the bare BFF root — either way it's
    // outside this SPA's router. Same discipline as LoginView.vue:87.
    // isSubmitting deliberately stays true: the page is navigating away, so
    // there's no more "submitting" state left to show.
    window.location.href = result.redirectTo
    return
  }

  switch (result.kind) {
    case 'invalid':
      // Generic, enumeration-safe — matches ResetPasswordView's posture,
      // but the recovery path differs: rotation has no self-service resend
      // (reissue is platform-admin-only), so pointing the user at "request
      // a new one" would send them to a door that doesn't exist.
      bannerMessage.value =
        'This link is invalid or has expired. Ask your administrator to send you a new one.'
      break
    case 'missingRedirect':
      succeededWithoutRedirect.value = true
      break
    case 'error':
    default:
      bannerMessage.value = 'Something went wrong. Please try again.'
      break
  }
  isSubmitting.value = false
}
</script>

<template>
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
            Set your password for {{ companyLabel(branding) }}
          </h1>
          <p class="text-sm text-muted-foreground">
            Choose a password to finish setting up your account.
          </p>
        </div>
      </template>

      <!-- Incomplete link: token/client_id missing — never rendered as a form. -->
      <div v-if="linkIncomplete" class="flex flex-col gap-4 text-center">
        <p role="alert" class="text-sm text-destructive">
          This link is incomplete. Please use the link from your email.
        </p>
      </div>

      <!-- Password was changed but we couldn't resolve where to send the user next. -->
      <div v-else-if="succeededWithoutRedirect" class="flex flex-col gap-4 text-center">
        <p class="text-sm text-foreground">
          Your password was updated, but we couldn't return you to your app. Try signing in.
        </p>
        <RouterLink :to="loginPath">
          <Button class="w-full">Continue to sign in</Button>
        </RouterLink>
      </div>

      <NewPasswordFields
        v-else
        id-prefix="password-rotation"
        submit-label="Set password and continue"
        submitting-label="Setting password…"
        :is-submitting="isSubmitting"
        :banner-message="bannerMessage"
        :min-length="8"
        @submit="handleSubmit"
      />
    </AuthShell>
  </TenantBrandingProvider>
</template>
