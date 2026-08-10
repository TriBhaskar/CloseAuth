<script setup lang="ts">
// Stage UI-3c: the clients surface, built honestly around a real backend
// gap — TenantClientController implements only create + a tenant-scoped
// get, no list (SAS's RegisteredClientRepository exposes no tenant-scoped
// list/delete; flagged, not silently built around, see
// TEST_COVERAGE_INVENTORY.md). So this page is NOT a list: it's a register
// form (the only creation path) plus a look-up-by-record-id control (the
// only lookup path), and it says so in plain language rather than showing
// an empty table that implies a list exists.
//
// No secret input anywhere on this page — the backend generates it
// (ClientSecretGenerator). A successful create stashes the response in
// tenantAdminClientCredentials.ts's store and navigates to the write-once
// handoff view; it never renders inline here.
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import FormField from '@/components/admin/FormField.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import { getClient, registerClient } from '@/api/tenantAdminClients'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const credentialsStore = useTenantAdminClientCredentialsStore()

// ---- register form ------------------------------------------------------

const form = reactive({
  clientId: '',
  clientName: '',
  publicClient: false,
  grantAuthorizationCode: true,
  grantRefreshToken: true,
  grantClientCredentials: false,
  redirectUris: '',
  scopes: '',
  requireProofKey: true,
  trusted: false,
})
const errors = reactive<Record<string, string>>({})
const banner = ref('')
const isSubmitting = ref(false)

function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((v) => v.trim())
    .filter(Boolean)
}

async function handleRegister(): Promise<void> {
  if (isSubmitting.value) return
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]

  const grantTypes: string[] = []
  if (form.grantAuthorizationCode) grantTypes.push('authorization_code')
  if (form.grantRefreshToken) grantTypes.push('refresh_token')
  if (form.grantClientCredentials) grantTypes.push('client_credentials')

  isSubmitting.value = true
  try {
    const result = await registerClient(slug, {
      clientId: form.clientId,
      clientName: form.clientName,
      publicClient: form.publicClient,
      grantTypes,
      scopes: splitList(form.scopes),
      redirectUris: splitList(form.redirectUris),
      requireProofKey: form.requireProofKey,
      trusted: form.trusted,
    })
    switch (result.kind) {
      case 'ok':
        credentialsStore.set(result.value, 'create')
        void router.push({ name: 'tenant-admin-client-credentials', params: { slug } })
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
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

// ---- look up by record id ------------------------------------------------

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const lookupId = ref('')
const lookupError = ref('')
const isLookingUp = ref(false)

async function handleLookup(): Promise<void> {
  if (isLookingUp.value) return
  lookupError.value = ''
  const id = lookupId.value.trim()
  if (!UUID_PATTERN.test(id)) {
    lookupError.value =
      'That does not look like a console record id (a UUID). This is the id shown on a client\'s credentials page, not its OAuth2 client_id.'
    return
  }
  isLookingUp.value = true
  try {
    const result = await getClient(slug, id)
    switch (result.kind) {
      case 'ok':
        void router.push({ name: 'tenant-admin-client-detail', params: { slug, clientId: id } })
        break
      case 'reauth':
        break
      case 'error':
        lookupError.value =
          result.status === 404
            ? 'No client with that record id exists in this tenant.'
            : describeAdminError(result)
        break
      default:
        lookupError.value = describeAdminError(result)
        break
    }
  } finally {
    isLookingUp.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Clients</h1>
      <p class="text-sm text-muted-foreground">Register OAuth2 clients for applications that authenticate against this tenant.</p>
    </div>

    <div id="clients-no-list-notice" class="rounded-lg border border-border bg-muted/40 p-4 text-sm text-muted-foreground">
      There is no list of clients here — the backend does not yet expose one for this tenant (a known, tracked gap).
      Save the console record id shown after registering a client; you'll need it to find that client again. Clients
      also each auto-create a matching resource server, visible in the
      <RouterLink :to="{ name: 'tenant-admin-resource-servers', params: { slug } }" class="underline underline-offset-2">
        resource servers list
      </RouterLink>
      with a "Created with a client" source.
    </div>

    <div class="rounded-xl border border-border p-6 flex flex-col gap-3">
      <h2 class="text-sm font-semibold">Look up a client</h2>
      <p class="text-xs text-muted-foreground">
        By console record id (not the OAuth2 client_id) — shown on the credentials page after registering or
        regenerating a secret.
      </p>
      <form id="client-lookup-form" class="flex items-end gap-2" novalidate @submit.prevent="handleLookup">
        <div class="flex-1 flex flex-col gap-1.5">
          <Label for="client-lookup-id">Console record id</Label>
          <Input
            id="client-lookup-id"
            v-model="lookupId"
            type="text"
            :disabled="isLookingUp"
            :aria-invalid="Boolean(lookupError)"
            aria-describedby="client-lookup-error"
          />
        </div>
        <Button id="client-lookup-submit" type="submit" variant="outline" :disabled="isLookingUp">
          {{ isLookingUp ? 'Looking up…' : 'Look up' }}
        </Button>
      </form>
      <p v-if="lookupError" id="client-lookup-error" role="alert" class="text-sm text-destructive">{{ lookupError }}</p>
    </div>

    <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <h2 class="text-sm font-semibold">Register a client</h2>
      <form id="client-register-form" class="flex flex-col gap-4" novalidate @submit.prevent="handleRegister">
        <FormField id="new-client-client-id" label="OAuth2 client_id" :error="errors.clientId">
          <template #default="{ hasError, describedBy }">
            <Input
              id="new-client-client-id"
              v-model="form.clientId"
              type="text"
              required
              :disabled="isSubmitting"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <FormField id="new-client-client-name" label="Display name" :error="errors.clientName">
          <template #default="{ hasError, describedBy }">
            <Input
              id="new-client-client-name"
              v-model="form.clientName"
              type="text"
              required
              :disabled="isSubmitting"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <div class="flex items-start gap-2">
          <Checkbox
            id="new-client-public"
            :model-value="form.publicClient"
            :disabled="isSubmitting"
            @update:model-value="(v) => (form.publicClient = Boolean(v))"
          />
          <Label for="new-client-public" class="font-normal leading-snug">
            Public client (no secret; requires PKCE). Leave unchecked for a confidential client — the backend will
            generate its secret.
          </Label>
        </div>

        <div class="flex flex-col gap-2">
          <Label>Grant types</Label>
          <div class="flex items-center gap-2">
            <Checkbox
              id="new-client-grant-auth-code"
              :model-value="form.grantAuthorizationCode"
              :disabled="isSubmitting"
              @update:model-value="(v) => (form.grantAuthorizationCode = Boolean(v))"
            />
            <Label for="new-client-grant-auth-code" class="font-normal">authorization_code</Label>
          </div>
          <div class="flex items-center gap-2">
            <Checkbox
              id="new-client-grant-refresh"
              :model-value="form.grantRefreshToken"
              :disabled="isSubmitting"
              @update:model-value="(v) => (form.grantRefreshToken = Boolean(v))"
            />
            <Label for="new-client-grant-refresh" class="font-normal">refresh_token</Label>
          </div>
          <div class="flex items-center gap-2">
            <Checkbox
              id="new-client-grant-client-credentials"
              :model-value="form.grantClientCredentials"
              :disabled="isSubmitting"
              @update:model-value="(v) => (form.grantClientCredentials = Boolean(v))"
            />
            <Label for="new-client-grant-client-credentials" class="font-normal">client_credentials</Label>
          </div>
          <p v-if="errors.grantTypes" role="alert" class="text-sm text-destructive">{{ errors.grantTypes }}</p>
        </div>

        <FormField id="new-client-redirect-uris" label="Redirect URIs (comma or newline separated)" :error="errors.redirectUris" hint="Required for authorization_code.">
          <template #default="{ hasError, describedBy }">
            <Input
              id="new-client-redirect-uris"
              v-model="form.redirectUris"
              type="text"
              :disabled="isSubmitting"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <FormField id="new-client-scopes" label="Scopes (comma or newline separated, optional)" :error="errors.scopes">
          <template #default="{ hasError, describedBy }">
            <Input
              id="new-client-scopes"
              v-model="form.scopes"
              type="text"
              :disabled="isSubmitting"
              :aria-invalid="hasError"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>

        <div class="flex items-start gap-2">
          <Checkbox
            id="new-client-require-pkce"
            :model-value="form.requireProofKey"
            :disabled="isSubmitting"
            @update:model-value="(v) => (form.requireProofKey = Boolean(v))"
          />
          <Label for="new-client-require-pkce" class="font-normal leading-snug">Require PKCE</Label>
        </div>

        <div class="flex items-start gap-2">
          <Checkbox
            id="new-client-trusted"
            :model-value="form.trusted"
            :disabled="isSubmitting"
            @update:model-value="(v) => (form.trusted = Boolean(v))"
          />
          <Label for="new-client-trusted" class="font-normal leading-snug">
            Trusted (first-party) — skips the OAuth consent screen.
          </Label>
        </div>

        <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

        <Button id="new-client-submit" type="submit" class="self-start" :disabled="isSubmitting">
          {{ isSubmitting ? 'Registering…' : 'Register client' }}
        </Button>
      </form>
    </div>
  </div>
</template>
