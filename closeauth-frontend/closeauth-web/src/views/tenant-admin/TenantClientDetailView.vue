<script setup lang="ts">
// Stage UI-3c, rebuilt FE-4c: client detail. The route param is the console
// record id (the SAS internal PK), never the OAuth2 client_id — see
// tenantAdminClients.ts's getClient doc comment. The secret is never shown
// here; it only ever exists in TenantClientCredentialsView.vue, reached via
// the rotate action below when the admin needs a new one.
//
// Now genuinely tabbed (Configuration · Credentials · Branding) per spec
// §6.4.3, tab held in the URL query — same reka-ui Tabs pattern FE-4a
// established (data-tab attribute as the stable test/CSS hook, since
// TabsTrigger generates its own id that wins over any id passed at the call
// site).
//
// Credentials' rotate action is now gated behind TypedConfirmDialog (match
// text = the client's client_id) per spec's literal "typed confirm" — a
// step up from the plain ConfirmDialog this page used before FE-4c.
//
// Branding is a named tab per spec but deliberately not built this session:
// per-client branding overrides need a new table/entity/service/controller/
// BFF surface disproportionate to this session's scope (tenant_branding is
// a real 1:1 with a tenant today, not a per-client table) — recorded as a
// tracked backend dependency, shown here as an honest placeholder rather
// than a fabricated feature (same precedent as WorkspaceEntryPlaceholderView
// and FE-4d's own current-session-identification gap on TenantAccountView).
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import QueryState from '@/components/admin/QueryState.vue'
import TypedConfirmDialog from '@/components/common/TypedConfirmDialog.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { describeAdminError } from '@/api/problem'
import { getClient, regenerateClientSecret, type ClientView } from '@/api/tenantAdminClients'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const clientRecordId = String(route.params.clientId ?? '')
const credentialsStore = useTenantAdminClientCredentialsStore()

// ---- tabs, URL-linkable ----------------------------------------------

type DetailTab = 'configuration' | 'credentials' | 'branding'
const VALID_TABS: DetailTab[] = ['configuration', 'credentials', 'branding']

function initialTab(): DetailTab {
  const q = route.query.tab
  return typeof q === 'string' && VALID_TABS.includes(q as DetailTab) ? (q as DetailTab) : 'configuration'
}

const activeTab = ref<DetailTab>(initialTab())

watch(activeTab, (tab) => {
  void router.replace({ query: { ...route.query, tab } })
})

// ---- client ----------------------------------------------------------

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

// ---- rotate secret ---------------------------------------------------

const isConfirmOpen = ref(false)
const isRotating = ref(false)
const rotateError = ref('')

async function confirmRotate(): Promise<void> {
  if (isRotating.value || !client.value) return
  rotateError.value = ''
  isRotating.value = true
  try {
    const result = await regenerateClientSecret(slug, clientRecordId)
    switch (result.kind) {
      case 'ok':
        isConfirmOpen.value = false
        credentialsStore.set(result.value, 'regenerate')
        void router.push({ name: 'tenant-admin-client-credentials', params: { slug } })
        break
      case 'conflict':
        rotateError.value =
          result.code === 'client.public_no_secret'
            ? 'This is a public client — it has no secret to regenerate.'
            : result.message
        break
      case 'reauth':
        break
      default:
        rotateError.value = describeAdminError(result)
        break
    }
  } finally {
    isRotating.value = false
  }
}

const canRotate = computed(() => client.value !== null && !client.value.publicClient)
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToClients">&larr; Back to clients</Button>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div v-if="client" class="flex flex-col gap-6">
        <div class="flex items-center justify-between">
          <div>
            <h1 id="client-detail-name" class="text-xl font-semibold tracking-tight">{{ client.clientName }}</h1>
            <IdentifierChip kind="client" :value="client.clientId" />
          </div>
          <Badge :variant="client.publicClient ? 'outline' : 'default'">
            {{ client.publicClient ? 'Public' : 'Confidential' }}
          </Badge>
        </div>

        <Tabs :model-value="activeTab" @update:model-value="(v) => (activeTab = v as DetailTab)">
          <TabsList>
            <!-- reka-ui's TabsTrigger generates its OWN id (Primitive :id="triggerId"),
                 which wins over any id we pass in — data-tab is the stable test/CSS hook instead. -->
            <TabsTrigger data-tab="configuration" value="configuration">Configuration</TabsTrigger>
            <TabsTrigger data-tab="credentials" value="credentials">Credentials</TabsTrigger>
            <TabsTrigger data-tab="branding" value="branding">Branding</TabsTrigger>
          </TabsList>

          <TabsContent value="configuration">
            <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
              <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                <dt class="text-muted-foreground">Console record id</dt>
                <dd class="font-mono break-all">{{ client.id }}</dd>
                <dt class="text-muted-foreground">Grant types</dt>
                <dd>{{ client.grantTypes.join(', ') || '—' }}</dd>
                <dt class="text-muted-foreground">Scopes</dt>
                <dd>{{ client.scopes.join(', ') || '—' }}</dd>
                <dt class="text-muted-foreground">Redirect URIs</dt>
                <dd class="break-all">{{ client.redirectUris.join(', ') || '—' }}</dd>
                <dt class="text-muted-foreground">Post-logout redirect URIs</dt>
                <dd class="break-all">{{ client.postLogoutRedirectUris.join(', ') || '—' }}</dd>
              </dl>
            </div>
          </TabsContent>

          <TabsContent value="credentials">
            <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
              <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                <dt class="text-muted-foreground">Created</dt>
                <dd><RelativeTime :value="client.createdAt" /></dd>
                <dt class="text-muted-foreground">Secret last rotated</dt>
                <dd>
                  <RelativeTime v-if="client.secretRotatedAt" :value="client.secretRotatedAt" />
                  <span v-else class="text-muted-foreground">Never rotated</span>
                </dd>
              </dl>

              <p class="text-sm text-muted-foreground border-t border-border pt-4">
                The client secret cannot be shown here — it was displayed exactly once, at creation (or at its last
                rotation). Only its hash is stored.
              </p>

              <div v-if="canRotate" class="flex flex-col gap-2">
                <Button id="client-regenerate-secret" variant="outline" class="self-start" @click="isConfirmOpen = true">
                  Rotate secret
                </Button>
              </div>
              <p v-else class="text-sm text-muted-foreground">
                This is a public client (PKCE-only) — it has no secret, so there is nothing to regenerate.
              </p>
              <p v-if="rotateError" role="alert" class="text-sm text-destructive">{{ rotateError }}</p>
            </div>
          </TabsContent>

          <TabsContent value="branding">
            <div class="rounded-xl border border-border p-6">
              <EmptyState
                title="Per-client branding isn't available yet."
                description="Overriding the tenant's hosted-page branding for just this client — with a live preview — needs a new backend capability that doesn't exist today. Tracked, not built."
              />
            </div>
          </TabsContent>
        </Tabs>
      </div>
    </QueryState>

    <TypedConfirmDialog
      v-if="client"
      :open="isConfirmOpen"
      title="Rotate this client's secret?"
      description="The current secret stops working immediately: anything using it to authenticate or refresh tokens will fail until it is updated with the new one. Already-issued access tokens keep working until they expire — this does not revoke live sessions, only the client's ability to authenticate."
      :match-text="client.clientId"
      match-label="Type the client_id to confirm:"
      confirm-label="Rotate"
      :pending="isRotating"
      @update:open="(open) => (isConfirmOpen = open)"
      @confirm="confirmRotate"
    />
  </div>
</template>
