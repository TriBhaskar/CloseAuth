<script setup lang="ts">
// FE-2a (spec §6.1): the real workspace entry screen — replaces the FE-0
// placeholder (see git history for that file's own header comment on why a
// placeholder existed at all). Kept at the same file path/component name
// deliberately: it's still the SAME route (`/`), and a11ySmoke.spec.ts's
// entry-route smoke check already targets this component by name.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EntryShell from '@/shells/EntryShell.vue'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import FormField from '@/components/common/FormField.vue'
import { normalizeTenantId, isValidTenantId } from '@/lib/tenantId'
import { resolveTenant } from '@/api/entryResolve'

const router = useRouter()

// spec §6.1 "idle" state: localStorage, canonical form only.
const STORAGE_KEY = 'closeauth.lastTenantId'

function readRemembered(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored && isValidTenantId(stored) ? stored : ''
  } catch {
    return ''
  }
}

function rememberTenantId(id: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, id)
  } catch {
    // Storage unavailable (private browsing, quota) — resolution still
    // proceeds, it just won't be pre-filled next visit.
  }
}

function forgetTenantId(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // see rememberTenantId
  }
}

const MALFORMED_ERROR =
  "That doesn't look like a tenant ID. It looks like ten_acme-inc — ask your administrator if you're not sure."
const NOT_FOUND_ERROR = "We couldn't find that workspace. Check the ID with your administrator."
const NETWORK_ERROR = 'Something went wrong. Please try again.'

const tenantId = ref('')
const hasRemembered = ref(false)
const rememberedDisplayName = ref('')
const isResolving = ref(false)
const errorMessage = ref('')

// Best-effort, silent: resolves the remembered ID's display name for the
// "Continue to Acme" button copy (spec §6.1). A failure here (the tenant
// has since gone unknown/suspended, or the network is down) leaves the
// field pre-filled but the button reading plain "Continue" — the user
// hasn't submitted anything yet, so there is nothing to show an error for.
onMounted(async () => {
  const remembered = readRemembered()
  if (!remembered) return
  tenantId.value = remembered
  hasRemembered.value = true
  const result = await resolveTenant(remembered)
  if (result.kind === 'ok') {
    rememberedDisplayName.value = result.value.displayName
  }
})

function handleBlur(): void {
  tenantId.value = normalizeTenantId(tenantId.value)
}

function useDifferentWorkspace(): void {
  forgetTenantId()
  hasRemembered.value = false
  rememberedDisplayName.value = ''
  tenantId.value = ''
  errorMessage.value = ''
}

async function handleContinue(): Promise<void> {
  if (isResolving.value) return
  errorMessage.value = ''

  const normalized = normalizeTenantId(tenantId.value)
  tenantId.value = normalized

  if (!isValidTenantId(normalized)) {
    errorMessage.value = MALFORMED_ERROR
    return
  }

  isResolving.value = true
  const result = await resolveTenant(normalized)
  isResolving.value = false

  if (result.kind === 'ok') {
    rememberTenantId(result.value.tenantId)
    router.push(`/t/${result.value.tenantId}`)
    return
  }
  // Unknown, suspended, and rate-limited all collapse into the SAME
  // notFound kind (see entryResolve.ts) — this is exactly spec §6.1's
  // "a suspended tenant must not be distinguishable from a nonexistent
  // one" requirement, enforced by construction rather than by care taken
  // here.
  if (result.kind === 'notFound') {
    errorMessage.value = NOT_FOUND_ERROR
    return
  }
  errorMessage.value = NETWORK_ERROR
}

const continueLabel = computed(() => {
  if (isResolving.value) return 'Continuing…'
  if (hasRemembered.value && rememberedDisplayName.value) return `Continue to ${rememberedDisplayName.value}`
  return 'Continue'
})
</script>

<template>
  <EntryShell>
    <template #above>
      <div class="flex flex-col items-center gap-2 text-center">
        <h1 class="text-xl font-semibold tracking-tight">Sign in to your workspace</h1>
      </div>
    </template>

    <form class="flex flex-col gap-4" novalidate @submit.prevent="handleContinue">
      <FormField
        id="entry-tenant-id"
        label="Tenant ID"
        :error="errorMessage"
        hint="Your administrator gave you this, or you'll find it in your sign-in link."
      >
        <template #default="{ hasError, describedBy }">
          <Input
            id="entry-tenant-id"
            v-model="tenantId"
            type="text"
            class="font-mono"
            placeholder="ten_acme-inc"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            required
            :disabled="isResolving"
            :aria-invalid="hasError"
            :aria-describedby="describedBy"
            @blur="handleBlur"
          />
        </template>
      </FormField>

      <Button type="submit" class="w-full" :disabled="isResolving">
        {{ continueLabel }}
      </Button>

      <button
        v-if="hasRemembered"
        type="button"
        class="text-sm text-center text-muted-foreground hover:underline"
        @click="useDifferentWorkspace"
      >
        Use a different workspace
      </button>
    </form>

    <template #footer>
      <div class="flex flex-col gap-4">
        <div class="flex items-center gap-3">
          <div class="h-px flex-1 bg-border" />
          <span class="text-xs text-muted-foreground">or</span>
          <div class="h-px flex-1 bg-border" />
        </div>
        <RouterLink to="/platform/login" class="text-sm text-center text-muted-foreground hover:underline">
          Platform administrator? Sign in
        </RouterLink>
      </div>
    </template>
  </EntryShell>
</template>
