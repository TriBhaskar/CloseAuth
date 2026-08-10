<script setup lang="ts">
// Stage UI-3c: client detail. The route param is the console record id (the
// SAS internal PK), never the OAuth2 client_id — see tenantAdminClients.ts's
// getClient doc comment. The secret is never shown here; it only ever
// exists in TenantClientCredentialsView.vue, reached via the regenerate
// action below when the admin needs a new one.
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import QueryState from '@/components/admin/QueryState.vue'
import ConfirmDialog from '@/components/admin/ConfirmDialog.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import { getClient, regenerateClientSecret, type ClientView } from '@/api/tenantAdminClients'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const clientRecordId = String(route.params.clientId ?? '')
const credentialsStore = useTenantAdminClientCredentialsStore()

const client = ref<ClientView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function loadClient(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getClient(slug, clientRecordId)
  switch (result.kind) {
    case 'ok':
      client.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      client.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(loadClient)

function backToClients(): void {
  void router.push({ name: 'tenant-admin-clients', params: { slug } })
}

// ---- regenerate secret ---------------------------------------------------

const isConfirmOpen = ref(false)
const isRegenerating = ref(false)
const regenerateError = ref('')

async function confirmRegenerate(): Promise<void> {
  if (isRegenerating.value) return
  regenerateError.value = ''
  isRegenerating.value = true
  try {
    const result = await regenerateClientSecret(slug, clientRecordId)
    switch (result.kind) {
      case 'ok':
        isConfirmOpen.value = false
        credentialsStore.set(result.value, 'regenerate')
        void router.push({ name: 'tenant-admin-client-credentials', params: { slug } })
        break
      case 'conflict':
        regenerateError.value =
          result.code === 'client.public_no_secret'
            ? 'This is a public client — it has no secret to regenerate.'
            : result.message
        break
      case 'reauth':
        break
      default:
        regenerateError.value = describeAdminError(result)
        break
    }
  } finally {
    isRegenerating.value = false
  }
}

const canRegenerate = computed(() => client.value !== null && !client.value.publicClient)
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToClients">&larr; Back to clients</Button>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div v-if="client" class="flex flex-col gap-6">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <h1 id="client-detail-name" class="text-xl font-semibold tracking-tight">{{ client.clientName }}</h1>
              <p class="text-sm text-muted-foreground font-mono">{{ client.clientId }}</p>
            </div>
            <Badge :variant="client.publicClient ? 'outline' : 'default'">
              {{ client.publicClient ? 'Public' : 'Confidential' }}
            </Badge>
          </div>

          <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt class="text-muted-foreground">Console record id</dt>
            <dd class="font-mono break-all">{{ client.id }}</dd>
            <dt class="text-muted-foreground">Grant types</dt>
            <dd>{{ client.grantTypes.join(', ') || '—' }}</dd>
            <dt class="text-muted-foreground">Scopes</dt>
            <dd>{{ client.scopes.join(', ') || '—' }}</dd>
            <dt class="text-muted-foreground">Redirect URIs</dt>
            <dd class="break-all">{{ client.redirectUris.join(', ') || '—' }}</dd>
          </dl>

          <p class="text-sm text-muted-foreground border-t border-border pt-4">
            The client secret cannot be shown here — it was displayed exactly once, at creation. Only its hash is
            stored.
          </p>

          <div v-if="canRegenerate" class="flex flex-col gap-2">
            <Button id="client-regenerate-secret" variant="outline" class="self-start" @click="isConfirmOpen = true">
              Regenerate client secret
            </Button>
          </div>
          <p v-else class="text-sm text-muted-foreground">
            This is a public client (PKCE-only) — it has no secret, so there is nothing to regenerate.
          </p>
          <p v-if="regenerateError" role="alert" class="text-sm text-destructive">{{ regenerateError }}</p>
        </div>
      </div>
    </QueryState>

    <ConfirmDialog
      :open="isConfirmOpen"
      title="Regenerate this client's secret?"
      description="The current secret stops working immediately: anything using it to authenticate or refresh tokens will fail until it is updated with the new one. Already-issued access tokens keep working until they expire — this does not revoke live sessions, only the client's ability to authenticate."
      confirm-label="Regenerate"
      :pending="isRegenerating"
      @update:open="(open) => (isConfirmOpen = open)"
      @confirm="confirmRegenerate"
    />
  </div>
</template>
