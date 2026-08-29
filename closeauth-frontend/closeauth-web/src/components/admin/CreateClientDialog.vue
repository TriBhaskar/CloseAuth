<script setup lang="ts">
// FE-4c: spec §6.4.3's three-step create-client wizard, replacing
// TenantClientsView.vue's old flat single-form register control. Lives in
// components/admin/ (feature-specific, one consumer today) matching
// ApplicationRolesPanel.vue's own precedent.
//
// Step 1 (Type) picks a client-type card, which fully determines grant
// types / auth method / PKCE — the mapping is spec's own card subtitles
// ("authorization_code + PKCE, confidential" / "client_credentials")
// reasoned out to the concrete backend fields (see TYPE_CONFIG below), not
// spec-enumerated field-by-field.
//
// Step 2 (Details) has no client_id field — the backend now derives the
// OAuth2 client_id from the client name (ClientIdGenerator), the same
// "nothing for an operator to usefully type" reasoning FE-4c already applied
// to the client secret. The generated id is shown on the post-registration
// credentials handoff view, never entered here. `trusted` stays in this
// step, unlisted in spec's three-field enumeration but present in the
// pre-wizard form and materially useful — dropping working,
// spec-uncontradicted functionality on a wireframe omission would be a
// regression.
//
// Redirect-URI/post-logout-URI validation (absolute, no fragment, https
// unless localhost) is spec's own rule, applied uniformly across all four
// types including Native/mobile — spec states no exception for custom URI
// schemes, and inventing one unasked would be scope creep. Flagged as a
// likely future gap if native-app onboarding is ever exercised for real.
//
// Step 3 (Scopes) uses the newly-extracted ScopeSelector.vue over a
// client-side-composed tenant-wide catalog (api/tenantAdminScopeCatalog.ts —
// no tenant-wide backend endpoint exists, so this fetches per-RS in
// parallel). Machine-to-machine hides steps 2's redirect/post-logout rows
// entirely (the grant never uses them) but still shows the Scopes step (M2M
// scopes are granted directly, no consent).
//
// On success: same registerClient -> credentials-store -> navigate-to-
// handoff flow TenantClientsView.vue always had, now owned here directly
// (matching TenantClientDetailView.vue's own precedent of a component
// driving its own store/router side effects rather than emitting upward).
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import FormField from '@/components/common/FormField.vue'
import ScopeSelector from '@/components/common/ScopeSelector.vue'
import { describeAdminError } from '@/api/problem'
import { registerClient } from '@/api/tenantAdminClients'
import { loadScopeCatalog, type ScopeCatalogGroup } from '@/api/tenantAdminScopeCatalog'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'

const props = defineProps<{ open: boolean; slug: string }>()
const emit = defineEmits<{ 'update:open': [open: boolean] }>()

const router = useRouter()
const credentialsStore = useTenantAdminClientCredentialsStore()

type ClientType = 'web' | 'spa' | 'native' | 'm2m'

const TYPE_CONFIG: Record<
  ClientType,
  {
    label: string
    subtitle: string
    grantTypes: string[]
    publicClient: boolean
    requireProofKey: boolean
    needsRedirects: boolean
  }
> = {
  web: {
    label: 'Web application (server-side)',
    subtitle: 'authorization_code + PKCE, confidential',
    grantTypes: ['authorization_code', 'refresh_token'],
    publicClient: false,
    requireProofKey: true,
    needsRedirects: true,
  },
  spa: {
    label: 'Single-page app',
    subtitle: 'authorization_code + PKCE, public',
    grantTypes: ['authorization_code', 'refresh_token'],
    publicClient: true,
    requireProofKey: true,
    needsRedirects: true,
  },
  native: {
    label: 'Native / mobile',
    subtitle: 'authorization_code + PKCE, public',
    grantTypes: ['authorization_code', 'refresh_token'],
    publicClient: true,
    requireProofKey: true,
    needsRedirects: true,
  },
  m2m: {
    label: 'Machine-to-machine',
    subtitle: 'client_credentials',
    grantTypes: ['client_credentials'],
    publicClient: false,
    requireProofKey: false,
    needsRedirects: false,
  },
}
const CLIENT_TYPES: ClientType[] = ['web', 'spa', 'native', 'm2m']

// ---- wizard state ---------------------------------------------------------

const step = ref<1 | 2 | 3>(1)
const selectedType = ref<ClientType | null>(null)

const details = reactive({
  clientName: '',
  trusted: false,
})
const redirectUris = ref<string[]>([''])
const postLogoutUris = ref<string[]>([''])
const redirectErrors = ref<Record<number, string>>({})
const postLogoutErrors = ref<Record<number, string>>({})
const detailErrors = reactive<Record<string, string>>({})

const selectedScopes = ref<Set<string>>(new Set())
const catalogGroups = ref<ScopeCatalogGroup[]>([])
const catalogTruncated = ref(false)
const isCatalogLoading = ref(false)
const catalogError = ref('')

const banner = ref('')
const isSubmitting = ref(false)

const needsRedirects = computed(() =>
  selectedType.value ? TYPE_CONFIG[selectedType.value].needsRedirects : false,
)

function resetWizard(): void {
  step.value = 1
  selectedType.value = null
  details.clientName = ''
  details.trusted = false
  redirectUris.value = ['']
  postLogoutUris.value = ['']
  redirectErrors.value = {}
  postLogoutErrors.value = {}
  for (const key of Object.keys(detailErrors)) delete detailErrors[key]
  selectedScopes.value = new Set()
  catalogGroups.value = []
  catalogTruncated.value = false
  catalogError.value = ''
  banner.value = ''
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) resetWizard()
  },
)

function close(): void {
  emit('update:open', false)
}

// ---- step 1: type -----------------------------------------------------

function chooseType(type: ClientType): void {
  selectedType.value = type
}

function goToDetails(): void {
  if (!selectedType.value) return
  step.value = 2
}

// ---- step 2: details ----------------------------------------------------

function addRedirectRow(): void {
  redirectUris.value.push('')
}
function removeRedirectRow(index: number): void {
  redirectUris.value.splice(index, 1)
}
function addPostLogoutRow(): void {
  postLogoutUris.value.push('')
}
function removePostLogoutRow(index: number): void {
  postLogoutUris.value.splice(index, 1)
}

// Spec's own rule: absolute URI, no fragment, https unless the host is
// localhost/127.0.0.1 — applied uniformly, including Native/mobile (no
// custom-scheme exception; see file header).
function validateUri(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return 'Must be an absolute URI.'
  }
  if (parsed.hash) return 'Must not include a fragment.'
  const isLocal = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1'
  if (parsed.protocol !== 'https:' && !isLocal)
    return 'Must use https, unless the host is localhost.'
  return null
}

function validateDetails(): boolean {
  for (const key of Object.keys(detailErrors)) delete detailErrors[key]
  redirectErrors.value = {}
  postLogoutErrors.value = {}
  let valid = true

  if (!details.clientName.trim()) {
    detailErrors.clientName = 'Enter a name for this client.'
    valid = false
  }

  if (needsRedirects.value) {
    const filledRedirects = redirectUris.value.filter((u) => u.trim())
    if (filledRedirects.length === 0) {
      detailErrors.redirectUris = 'At least one redirect URI is required for this client type.'
      valid = false
    }
    const nextRedirectErrors: Record<number, string> = {}
    redirectUris.value.forEach((uri, index) => {
      const error = validateUri(uri)
      if (error) {
        nextRedirectErrors[index] = error
        valid = false
      }
    })
    redirectErrors.value = nextRedirectErrors

    const nextPostLogoutErrors: Record<number, string> = {}
    postLogoutUris.value.forEach((uri, index) => {
      const error = validateUri(uri)
      if (error) {
        nextPostLogoutErrors[index] = error
        valid = false
      }
    })
    postLogoutErrors.value = nextPostLogoutErrors
  }

  return valid
}

async function goToScopes(): Promise<void> {
  if (!validateDetails()) return
  step.value = 3
  if (catalogGroups.value.length === 0 && !isCatalogLoading.value) {
    await fetchCatalog()
  }
}

function backToType(): void {
  step.value = 1
}
function backToDetails(): void {
  step.value = 2
}

// ---- step 3: scopes -------------------------------------------------------

async function fetchCatalog(): Promise<void> {
  isCatalogLoading.value = true
  catalogError.value = ''
  const result = await loadScopeCatalog(props.slug)
  isCatalogLoading.value = false
  if (result === null) {
    catalogError.value =
      'Could not load the scope catalog. You can still register the client without scopes and add them later.'
    return
  }
  catalogGroups.value = result.groups
  catalogTruncated.value = result.truncated
}

// ---- submit -----------------------------------------------------------

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value || !selectedType.value) return
  banner.value = ''
  const config = TYPE_CONFIG[selectedType.value]

  isSubmitting.value = true
  try {
    const result = await registerClient(props.slug, {
      clientName: details.clientName.trim(),
      publicClient: config.publicClient,
      grantTypes: config.grantTypes,
      scopes: Array.from(selectedScopes.value),
      redirectUris: needsRedirects.value
        ? redirectUris.value.map((u) => u.trim()).filter(Boolean)
        : [],
      postLogoutUris: needsRedirects.value
        ? postLogoutUris.value.map((u) => u.trim()).filter(Boolean)
        : [],
      requireProofKey: config.requireProofKey,
      trusted: details.trusted,
    })
    switch (result.kind) {
      case 'ok':
        credentialsStore.set(result.value, 'create')
        close()
        void router.push({ name: 'tenant-admin-client-credentials', params: { slug: props.slug } })
        break
      case 'validationErrors':
        step.value = 2
        Object.assign(detailErrors, result.errors)
        break
      case 'reauth':
        break
      default:
        banner.value = describeAdminError(result)
        break
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="(value: boolean) => emit('update:open', value)">
    <DialogContent id="create-client-wizard" class="max-w-xl">
      <DialogHeader>
        <DialogTitle>Register a client</DialogTitle>
        <DialogDescription>
          {{
            step === 1
              ? 'Client type determines everything downstream.'
              : step === 2
                ? 'Name and endpoints.'
                : 'Scopes this client can request.'
          }}
        </DialogDescription>
      </DialogHeader>

      <!-- Step 1: type -->
      <div v-if="step === 1" class="flex flex-col gap-3">
        <div
          v-for="type in CLIENT_TYPES"
          :key="type"
          class="rounded-lg border p-3 cursor-pointer flex flex-col gap-1"
          :class="selectedType === type ? 'border-primary bg-primary/5' : 'border-border'"
          @click="chooseType(type)"
        >
          <div class="flex items-center gap-2">
            <input
              :id="`client-wizard-type-${type}`"
              type="radio"
              name="client-wizard-type"
              :value="type"
              :checked="selectedType === type"
              class="size-4"
              @change="chooseType(type)"
            />
            <Label :for="`client-wizard-type-${type}`" class="font-medium">{{
              TYPE_CONFIG[type].label
            }}</Label>
          </div>
          <p class="text-xs text-muted-foreground font-mono pl-6">
            {{ TYPE_CONFIG[type].subtitle }}
          </p>
        </div>
      </div>

      <!-- Step 2: details -->
      <div v-else-if="step === 2" class="flex flex-col gap-4">
        <FormField
          id="client-wizard-client-name"
          label="Client name"
          :error="detailErrors.clientName"
        >
          <template #default="{ hasError, describedBy }">
            <Input
              id="client-wizard-client-name"
              v-model="details.clientName"
              type="text"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <template v-if="needsRedirects">
          <div class="flex flex-col gap-2">
            <Label>Redirect URIs</Label>
            <div v-for="(uri, index) in redirectUris" :key="index" class="flex flex-col gap-1">
              <div class="flex items-center gap-2">
                <Input
                  :id="`client-wizard-redirect-${index}`"
                  v-model="redirectUris[index]"
                  type="text"
                  placeholder="https://app.example.com/callback"
                  :aria-invalid="Boolean(redirectErrors[index])"
                />
                <Button
                  v-if="redirectUris.length > 1"
                  :id="`client-wizard-redirect-remove-${index}`"
                  type="button"
                  variant="ghost"
                  size="sm"
                  @click="removeRedirectRow(index)"
                >
                  Remove
                </Button>
              </div>
              <p v-if="redirectErrors[index]" role="alert" class="text-sm text-destructive">
                {{ redirectErrors[index] }}
              </p>
            </div>
            <Button
              id="client-wizard-redirect-add"
              type="button"
              variant="outline"
              size="sm"
              class="self-start"
              @click="addRedirectRow"
            >
              Add redirect URI
            </Button>
            <p v-if="detailErrors.redirectUris" role="alert" class="text-sm text-destructive">
              {{ detailErrors.redirectUris }}
            </p>
          </div>

          <div class="flex flex-col gap-2">
            <Label>Post-logout redirect URIs (optional)</Label>
            <div v-for="(uri, index) in postLogoutUris" :key="index" class="flex flex-col gap-1">
              <div class="flex items-center gap-2">
                <Input
                  :id="`client-wizard-post-logout-${index}`"
                  v-model="postLogoutUris[index]"
                  type="text"
                  placeholder="https://app.example.com/logged-out"
                  :aria-invalid="Boolean(postLogoutErrors[index])"
                />
                <Button
                  v-if="postLogoutUris.length > 1"
                  :id="`client-wizard-post-logout-remove-${index}`"
                  type="button"
                  variant="ghost"
                  size="sm"
                  @click="removePostLogoutRow(index)"
                >
                  Remove
                </Button>
              </div>
              <p v-if="postLogoutErrors[index]" role="alert" class="text-sm text-destructive">
                {{ postLogoutErrors[index] }}
              </p>
            </div>
            <Button
              id="client-wizard-post-logout-add"
              type="button"
              variant="outline"
              size="sm"
              class="self-start"
              @click="addPostLogoutRow"
            >
              Add post-logout URI
            </Button>
          </div>
        </template>

        <div class="flex items-start gap-2">
          <Checkbox
            id="client-wizard-trusted"
            :model-value="details.trusted"
            @update:model-value="(v) => (details.trusted = Boolean(v))"
          />
          <Label for="client-wizard-trusted" class="font-normal leading-snug">
            Trusted (first-party) — skips the OAuth consent screen.
          </Label>
        </div>
      </div>

      <!-- Step 3: scopes -->
      <div v-else class="flex flex-col gap-3">
        <p v-if="catalogTruncated" class="text-xs text-muted-foreground">
          More scopes exist than are shown here — some may be missing from this list.
        </p>
        <p v-if="isCatalogLoading" class="text-sm text-muted-foreground">Loading scope catalog…</p>
        <p v-else-if="catalogError" role="alert" class="text-sm text-destructive">
          {{ catalogError }}
        </p>
        <ScopeSelector v-else v-model="selectedScopes" :groups="catalogGroups" />
        <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>
      </div>

      <DialogFooter>
        <Button v-if="step === 1" type="button" variant="outline" @click="close">Cancel</Button>
        <Button
          v-if="step === 2"
          id="client-wizard-back"
          type="button"
          variant="outline"
          @click="backToType"
          >Back</Button
        >
        <Button
          v-if="step === 3"
          id="client-wizard-back"
          type="button"
          variant="outline"
          @click="backToDetails"
          :disabled="isSubmitting"
        >
          Back
        </Button>

        <Button
          v-if="step === 1"
          id="client-wizard-next"
          type="button"
          :disabled="!selectedType"
          @click="goToDetails"
        >
          Next
        </Button>
        <Button v-if="step === 2" id="client-wizard-next" type="button" @click="goToScopes"
          >Next</Button
        >
        <Button
          v-if="step === 3"
          id="client-wizard-submit"
          type="button"
          :disabled="isSubmitting"
          @click="handleSubmit"
        >
          {{ isSubmitting ? 'Registering…' : 'Register client' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
