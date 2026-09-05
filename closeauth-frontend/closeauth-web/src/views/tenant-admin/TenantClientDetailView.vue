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
//
// Client update/delete: the Configuration tab's read-only <dl> became an
// edit form, following TenantResourceServerDetailView.vue's exact shape
// (editForm/editErrors/editBanner/isSaving, resetEditForm seeded from the
// load, handleSaveEdit switching on the AdminResult kinds). clientId,
// tenantId, publicClient, and grantTypes have no field on UpdateClientCommand
// at all — rendered as read-only <code> blocks with copy explaining why,
// the house rule established by rs.audienceIdentifier and scope-name above.
// Redirect/post-logout URIs reuse UriListField.vue (extracted from
// CreateClientDialog.vue) and are only shown when the client's (immutable)
// grantTypes include authorization_code — the same "ignored otherwise"
// conditionality RegisterClientCommand's javadoc already documents. Scopes
// reuse ScopeSelector.vue over the same tenant-wide catalog the create
// wizard's step 3 already fetches.
//
// A Danger Zone below the tabs holds delete, gated behind TypedConfirmDialog
// (match text = client_id, the same value rotate already uses) — spec's own
// header comment on TypedConfirmDialog names "delete client" as an intended
// consumer. Both edit and delete are withheld entirely (an explanatory <p>
// replaces the control, never a button that would just 409) for the
// platform-managed admin-console client — clientActions() is the single
// source of truth for that gate, on both this page and the list.
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import QueryState from '@/components/admin/QueryState.vue'
import TypedConfirmDialog from '@/components/common/TypedConfirmDialog.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import FormField from '@/components/common/FormField.vue'
import UriListField from '@/components/common/UriListField.vue'
import ScopeSelector from '@/components/common/ScopeSelector.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import {
  getClient,
  regenerateClientSecret,
  updateClient,
  deleteClient,
  clientActions,
  CLIENT_CONFLICT_FIELDS,
  type ClientView,
} from '@/api/tenantAdminClients'
import { loadScopeCatalog, type ScopeCatalogGroup } from '@/api/tenantAdminScopeCatalog'
import { validateUriList } from '@/lib/uriValidation'
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
  return typeof q === 'string' && VALID_TABS.includes(q as DetailTab)
    ? (q as DetailTab)
    : 'configuration'
}

const activeTab = ref<DetailTab>(initialTab())

watch(activeTab, (tab) => {
  void router.replace({ query: { ...route.query, tab } })
})

// ---- client ----------------------------------------------------------

const client = ref<ClientView | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)

async function loadClient(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getClient(slug, clientRecordId)
  switch (result.kind) {
    case 'ok':
      client.value = result.value
      resetEditForm(result.value)
      isLoading.value = false
      if (actions.value.includes('edit')) {
        void fetchCatalog()
      }
      break
    case 'reauth':
      break
    default: {
      client.value = null
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

onMounted(loadClient)

function backToClients(): void {
  void router.push({ name: 'tenant-admin-clients', params: { slug } })
}

const actions = computed(() => (client.value ? clientActions(client.value) : []))

// authorization_code is the client's (immutable) grant that actually uses
// redirect/post-logout URIs — RegisterClientCommand's javadoc: "required for
// authorization_code; ignored otherwise." Editing them for an M2M client
// would be dead weight the backend silently ignores.
const needsRedirects = computed(() => client.value?.grantTypes.includes('authorization_code') ?? false)

// ---- edit configuration ------------------------------------------------

const editForm = reactive({ clientName: '', requireProofKey: false, trusted: false })
const editRedirectUris = ref<string[]>([''])
const editPostLogoutUris = ref<string[]>([''])
const editRedirectErrors = ref<Record<number, string>>({})
const editPostLogoutErrors = ref<Record<number, string>>({})
const editSelectedScopes = ref<Set<string>>(new Set())
const editErrors = reactive<Record<string, string>>({})
const editBanner = ref('')
const isSaving = ref(false)

const catalogGroups = ref<ScopeCatalogGroup[]>([])
const catalogTruncated = ref(false)
const isCatalogLoading = ref(false)
const catalogError = ref('')

async function fetchCatalog(): Promise<void> {
  isCatalogLoading.value = true
  catalogError.value = ''
  const result = await loadScopeCatalog(slug)
  isCatalogLoading.value = false
  if (result === null) {
    catalogError.value = 'Could not load the scope catalog. Scopes cannot be edited right now.'
    return
  }
  catalogGroups.value = result.groups
  catalogTruncated.value = result.truncated
}

function resetEditForm(value: ClientView): void {
  editForm.clientName = value.clientName
  editForm.requireProofKey = value.requireProofKey
  editForm.trusted = value.trusted
  editRedirectUris.value = value.redirectUris.length > 0 ? [...value.redirectUris] : ['']
  editPostLogoutUris.value =
    value.postLogoutRedirectUris.length > 0 ? [...value.postLogoutRedirectUris] : ['']
  editSelectedScopes.value = new Set(value.scopes)
  editBanner.value = ''
  editRedirectErrors.value = {}
  editPostLogoutErrors.value = {}
  for (const key of Object.keys(editErrors)) delete editErrors[key]
}

function validateEditForm(): boolean {
  for (const key of Object.keys(editErrors)) delete editErrors[key]
  editRedirectErrors.value = {}
  editPostLogoutErrors.value = {}
  let valid = true

  if (!editForm.clientName.trim()) {
    editErrors.clientName = 'Enter a name for this client.'
    valid = false
  }

  if (needsRedirects.value) {
    const filled = editRedirectUris.value.filter((u) => u.trim())
    if (filled.length === 0) {
      editErrors.redirectUris = 'At least one redirect URI is required for this client type.'
      valid = false
    }
    editRedirectErrors.value = validateUriList(editRedirectUris.value)
    editPostLogoutErrors.value = validateUriList(editPostLogoutUris.value)
    if (Object.keys(editRedirectErrors.value).length > 0 || Object.keys(editPostLogoutErrors.value).length > 0) {
      valid = false
    }
  }

  return valid
}

async function handleSaveEdit(): Promise<void> {
  if (isSaving.value || !client.value) return
  editBanner.value = ''
  if (!validateEditForm()) return

  isSaving.value = true
  try {
    const result = await updateClient(slug, clientRecordId, {
      clientName: editForm.clientName.trim(),
      scopes: Array.from(editSelectedScopes.value),
      redirectUris: needsRedirects.value
        ? editRedirectUris.value.map((u) => u.trim()).filter(Boolean)
        : [],
      postLogoutUris: needsRedirects.value
        ? editPostLogoutUris.value.map((u) => u.trim()).filter(Boolean)
        : [],
      requireProofKey: editForm.requireProofKey,
      trusted: editForm.trusted,
    })
    switch (result.kind) {
      case 'ok':
        client.value = result.value
        break
      case 'validationErrors':
        Object.assign(editErrors, result.errors)
        break
      case 'conflict': {
        const field = CLIENT_CONFLICT_FIELDS[result.code]
        if (field) editErrors[field] = result.message
        else editBanner.value = result.message
        break
      }
      case 'reauth':
        break
      default:
        editBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isSaving.value = false
  }
}

// ---- delete client ------------------------------------------------------

const isDeleting = ref(false)
const deleteError = ref('')

async function confirmDelete(): Promise<void> {
  if (isDeleting.value) return
  deleteError.value = ''
  isDeleting.value = true
  try {
    const result = await deleteClient(slug, clientRecordId)
    switch (result.kind) {
      case 'ok':
        pendingConfirm.value = null
        backToClients()
        break
      case 'conflict':
        deleteError.value = result.message
        pendingConfirm.value = null
        break
      case 'reauth':
        break
      default:
        deleteError.value = describeAdminError(result)
        pendingConfirm.value = null
        break
    }
  } finally {
    isDeleting.value = false
  }
}

// ---- rotate secret ---------------------------------------------------

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
        pendingConfirm.value = null
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

// Delegates to clientActions() (single source of truth, also gates the
// edit/delete controls below) rather than re-deriving !publicClient inline —
// the platform-managed console client is additionally withheld here even
// though it happens to be public too, for the same reason, not by accident.
const canRotate = computed(() => actions.value.includes('rotate'))

// ---- shared typed-confirm dialog (rotate + delete) ---------------------
//
// ONE dialog instance, not two: TypedConfirmDialog's ids
// (#typed-confirm-input, #typed-confirm-dialog-confirm) are static, and this
// page's test harness stubs the underlying Dialog primitive to always render
// its slot regardless of :open — two simultaneously-mounted instances would
// collide on those ids. Same "one dialog, a mode field" shape
// TenantResourceServerDetailView.vue uses for its add/edit scope dialog.
type PendingConfirm = 'rotate' | 'delete' | null
const pendingConfirm = ref<PendingConfirm>(null)

const confirmDialogConfig = computed(() => {
  if (pendingConfirm.value === 'rotate') {
    return {
      title: "Rotate this client's secret?",
      description:
        "The current secret stops working immediately: anything using it to authenticate or refresh tokens will fail until it is updated with the new one. Already-issued access tokens keep working until they expire — this does not revoke live sessions, only the client's ability to authenticate.",
      confirmLabel: 'Rotate',
      pending: isRotating.value,
    }
  }
  return {
    title: 'Delete this client?',
    description:
      'This is permanent: the client, its 1:1 auto-created resource server, and its stored authorizations/consents are all removed. Outstanding access tokens are not individually revoked — they simply expire on their own (within a few minutes).',
    confirmLabel: 'Delete',
    pending: isDeleting.value,
  }
})

function handleConfirm(): void {
  if (pendingConfirm.value === 'rotate') void confirmRotate()
  else if (pendingConfirm.value === 'delete') void confirmDelete()
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <Button variant="ghost" size="sm" class="self-start" @click="backToClients"
      >&larr; Back to clients</Button
    >

    <QueryState
      :loading="isLoading"
      :error="errorMessage"
      :retryable="errorRetryable"
      @retry="loadClient"
    >
      <div v-if="client" class="flex flex-col gap-6">
        <div class="flex items-center justify-between">
          <div>
            <h1 id="client-detail-name" class="text-xl font-semibold tracking-tight">
              {{ client.clientName }}
            </h1>
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
              </dl>
              <p class="text-xs text-muted-foreground">
                Client id, type (public/confidential), and grant types are immutable after
                registration — there is no way to change them here, by design.
              </p>

              <form
                v-if="actions.includes('edit')"
                id="client-edit-form"
                class="flex flex-col gap-4 border-t border-border pt-4"
                novalidate
                @submit.prevent="handleSaveEdit"
              >
                <FormField id="client-edit-name" label="Client name" :error="editErrors.clientName">
                  <template #default="{ hasError, describedBy }">
                    <Input
                      id="client-edit-name"
                      v-model="editForm.clientName"
                      type="text"
                      required
                      :disabled="isSaving"
                      :aria-invalid="hasError"
                      :aria-describedby="describedBy"
                    />
                  </template>
                </FormField>

                <template v-if="needsRedirects">
                  <UriListField
                    id-prefix="client-edit-redirect"
                    label="Redirect URIs"
                    placeholder="https://app.example.com/callback"
                    add-label="Add redirect URI"
                    v-model="editRedirectUris"
                    :errors="editRedirectErrors"
                    :group-error="editErrors.redirectUris"
                  />
                  <UriListField
                    id-prefix="client-edit-post-logout"
                    label="Post-logout redirect URIs (optional)"
                    placeholder="https://app.example.com/logged-out"
                    add-label="Add post-logout URI"
                    v-model="editPostLogoutUris"
                    :errors="editPostLogoutErrors"
                  />
                </template>

                <div class="flex flex-col gap-2">
                  <Label>Scopes</Label>
                  <p v-if="catalogTruncated" class="text-xs text-muted-foreground">
                    More scopes exist than are shown here — some may be missing from this list.
                  </p>
                  <p v-if="isCatalogLoading" class="text-sm text-muted-foreground">
                    Loading scope catalog…
                  </p>
                  <p v-else-if="catalogError" role="alert" class="text-sm text-destructive">
                    {{ catalogError }}
                  </p>
                  <ScopeSelector v-else v-model="editSelectedScopes" :groups="catalogGroups" />
                </div>

                <div class="flex items-start gap-2">
                  <Checkbox
                    id="client-edit-require-proof-key"
                    :model-value="editForm.requireProofKey"
                    :disabled="isSaving"
                    @update:model-value="(v) => (editForm.requireProofKey = Boolean(v))"
                  />
                  <Label for="client-edit-require-proof-key" class="font-normal leading-snug">
                    Require PKCE
                  </Label>
                </div>
                <div class="flex items-start gap-2">
                  <Checkbox
                    id="client-edit-trusted"
                    :model-value="editForm.trusted"
                    :disabled="isSaving"
                    @update:model-value="(v) => (editForm.trusted = Boolean(v))"
                  />
                  <Label for="client-edit-trusted" class="font-normal leading-snug">
                    Trusted (first-party) — skips the OAuth consent screen.
                  </Label>
                </div>

                <p v-if="editBanner" role="alert" class="text-sm text-destructive">{{ editBanner }}</p>
                <Button
                  id="client-save-edit"
                  type="submit"
                  variant="outline"
                  class="self-start"
                  :disabled="isSaving"
                >
                  {{ isSaving ? 'Saving…' : 'Save changes' }}
                </Button>
              </form>
              <p v-else class="text-sm text-muted-foreground border-t border-border pt-4">
                This is the tenant's admin-console client — it is platform-managed and cannot be
                edited here.
              </p>
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
                The client secret cannot be shown here — it was displayed exactly once, at creation
                (or at its last rotation). Only its hash is stored.
              </p>

              <div v-if="canRotate" class="flex flex-col gap-2">
                <Button
                  id="client-regenerate-secret"
                  variant="outline"
                  class="self-start"
                  @click="pendingConfirm = 'rotate'"
                >
                  Rotate secret
                </Button>
              </div>
              <p v-else class="text-sm text-muted-foreground">
                This is a public client (PKCE-only) — it has no secret, so there is nothing to
                regenerate.
              </p>
              <p v-if="rotateError" role="alert" class="text-sm text-destructive">
                {{ rotateError }}
              </p>
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

        <div class="rounded-xl border border-border p-6 flex flex-col gap-2">
          <h2 class="text-sm font-medium text-muted-foreground">Danger zone</h2>
          <Button
            v-if="actions.includes('delete')"
            id="client-delete-button"
            variant="destructive"
            class="self-start"
            @click="pendingConfirm = 'delete'"
          >
            Delete client
          </Button>
          <p v-else class="text-sm text-muted-foreground">
            This is the tenant's admin-console client — it is platform-managed and cannot be
            deleted.
          </p>
          <p v-if="deleteError" role="alert" class="text-sm text-destructive">
            {{ deleteError }}
          </p>
        </div>
      </div>
    </QueryState>

    <TypedConfirmDialog
      v-if="client && pendingConfirm"
      :open="pendingConfirm !== null"
      :title="confirmDialogConfig.title"
      :description="confirmDialogConfig.description"
      :match-text="client.clientId"
      match-label="Type the client_id to confirm:"
      :confirm-label="confirmDialogConfig.confirmLabel"
      :pending="confirmDialogConfig.pending"
      @update:open="
        (open) => {
          if (!open) pendingConfirm = null
        }
      "
      @confirm="handleConfirm"
    />
  </div>
</template>
