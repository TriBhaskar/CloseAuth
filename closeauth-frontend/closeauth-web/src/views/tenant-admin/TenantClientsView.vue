<script setup lang="ts">
// Stage UI-3c, rebuilt FE-4c: the clients surface, built honestly around a
// real backend gap — TenantClientController implements only create + a
// tenant-scoped get, no list (SAS's RegisteredClientRepository exposes no
// tenant-scoped list/delete; flagged, not silently built around, see
// TEST_COVERAGE_INVENTORY.md). So this page is NOT a list: it's a
// register-a-client action (via CreateClientDialog, FE-4c's three-step
// wizard) plus a look-up-by-record-id control (the only lookup path), said
// in plain language via EmptyState rather than an empty table implying a
// list exists — spec §6.4.3's own literal wording names EmptyState.
//
// The lookup control is intentionally "by console record id," not by the
// OAuth2 client_id — confirmed against ClientRegistrationService
// .loadClientOrThrow: GET /clients/{clientId} resolves via
// registeredClientRepository.findById(clientRegisteredId), the SAS internal
// record id, never the client_id field an application would present at
// token time. Spec's and the build plan's own "client_id lookup" wording is
// imprecise given that backend constraint — this control's behavior is
// correct as built, not a deviation to fix.
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import EmptyState from '@/components/common/EmptyState.vue'
import CreateClientDialog from '@/components/admin/CreateClientDialog.vue'
import { describeAdminError } from '@/api/problem'
import { getClient } from '@/api/tenantAdminClients'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

const isWizardOpen = ref(false)

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
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Clients</h1>
        <p class="text-sm text-muted-foreground">Register OAuth2 clients for applications that authenticate against this tenant.</p>
      </div>
      <Button id="open-create-client-wizard" @click="isWizardOpen = true">Register a client</Button>
    </div>

    <div id="clients-no-list-notice" class="rounded-lg border border-border bg-muted/40">
      <EmptyState
        title="There is no client list here yet."
        description="The backend does not yet expose a tenant-scoped list for clients (a known, tracked gap). Save the console record id shown after registering a client — you'll need it to find that client again."
      >
        <template #action>
          <p class="text-sm text-muted-foreground">
            Clients also each auto-create a matching resource server, visible in the
            <RouterLink :to="{ name: 'tenant-admin-resource-servers', params: { slug } }" class="underline underline-offset-2">
              resource servers list
            </RouterLink>
            with a "Created with a client" source.
          </p>
        </template>
      </EmptyState>
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

    <CreateClientDialog :open="isWizardOpen" :slug="slug" @update:open="(v) => (isWizardOpen = v)" />
  </div>
</template>
