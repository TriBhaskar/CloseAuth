<script setup lang="ts">
// Stage UI-3c: the write-once credential handoff — the centerpiece of this
// stage. Reached after BOTH client creation and secret regeneration
// (TenantClientsView.vue / TenantClientDetailView.vue write to
// tenantAdminClientCredentials.ts's in-memory store, then navigate here).
//
// Load-bearing decisions, each because getting this wrong leaves an admin
// with an unusable client:
//   - The secret is masked by default, revealed only on request, and never
//     defaults to visible — an admin screen-sharing or presenting shouldn't
//     leak it by simply landing on this page.
//   - "Continue" is gated behind an explicit acknowledgement checkbox, not
//     just a passive warning banner — the admin must make a deliberate
//     choice that they've saved the credentials before the store clears.
//   - The store is genuinely in-memory (see that file's header) — a
//     refresh, a bookmark, or revisiting after acknowledgement all
//     correctly show the "no longer available" state below, never a blank
//     field or (worse) a stale/fabricated value.
//   - The OAuth2 client_id and the console record id are shown as two
//     separately labelled, separately copyable fields — GET
//     /clients/{id} keys on the record id (the SAS internal PK), not the
//     client_id an admin would naturally assume, so conflating them here
//     would strand the admin later.
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { useTenantAdminClientCredentialsStore } from '@/stores/tenantAdminClientCredentials'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

const store = useTenantAdminClientCredentialsStore()

// Read once, synchronously, into local state — NOT a computed off the
// store — so that clearing the store on acknowledgement (below) doesn't
// yank the just-shown values out from under the admin before they navigate
// away. The store's own state after that point is irrelevant to this view.
const credentials = store.credentials
const context = store.context

const secretRevealed = ref(false)
const acknowledged = ref(false)
const copiedField = ref<'clientId' | 'recordId' | 'secret' | null>(null)

function maskedSecret(secret: string): string {
  return '•'.repeat(Math.min(secret.length, 32))
}

async function copy(field: 'clientId' | 'recordId' | 'secret', value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value)
    copiedField.value = field
    setTimeout(() => {
      if (copiedField.value === field) copiedField.value = null
    }, 2000)
  } catch {
    // Clipboard access can be denied/unavailable — the value is still
    // selectable/visible on screen, so this is a nicety, not a hard failure.
  }
}

function downloadCredentials(): void {
  if (!credentials) return
  const payload = {
    clientId: credentials.client.clientId,
    recordId: credentials.client.id,
    clientSecret: credentials.clientSecret,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${credentials.client.clientId}-credentials.json`
  link.click()
  URL.revokeObjectURL(url)
}

const canContinue = computed(() => acknowledged.value)

function handleContinue(): void {
  if (!canContinue.value || !credentials) return
  const recordId = credentials.client.id
  store.clear()
  void router.push({ name: 'tenant-admin-client-detail', params: { slug, clientId: recordId } })
}
</script>

<template>
  <div class="flex flex-col gap-6 max-w-2xl">
    <div v-if="!credentials" id="client-credentials-unavailable" class="rounded-xl border border-border p-6 flex flex-col gap-3">
      <h1 class="text-xl font-semibold tracking-tight">These credentials are no longer available</h1>
      <p class="text-sm text-muted-foreground">
        A client secret is shown exactly once, right after it's issued. This page only has something to show
        immediately after registering a client or regenerating its secret — a refresh, a bookmark, or a second visit
        loses it, by design; only the backend's hash survives, and the plaintext cannot be recovered.
      </p>
      <p class="text-sm text-muted-foreground">
        If you still need the secret, issue a new one from the client's detail page — that invalidates the old one.
      </p>
      <Button variant="outline" class="self-start" @click="router.push({ name: 'tenant-admin-clients', params: { slug } })">
        &larr; Back to clients
      </Button>
    </div>

    <div v-else class="flex flex-col gap-6">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">
          {{ context === 'regenerate' ? 'New secret issued' : 'Client registered' }}
        </h1>
        <p role="alert" class="text-sm font-medium text-destructive mt-1">
          These credentials are shown ONE TIME ONLY. Once you leave this page, the secret cannot be retrieved again —
          only its hash is stored. Copy or download it now.
        </p>
      </div>

      <div class="rounded-xl border border-border p-6 flex flex-col gap-5">
        <div class="flex flex-col gap-1.5">
          <Label>OAuth2 client_id</Label>
          <p class="text-xs text-muted-foreground">Put this in your application's OAuth configuration.</p>
          <div class="flex items-center gap-2">
            <code id="client-credentials-client-id" class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all">
              {{ credentials.client.clientId }}
            </code>
            <Button
              id="client-credentials-copy-client-id"
              type="button"
              variant="outline"
              size="sm"
              @click="copy('clientId', credentials.client.clientId)"
            >
              {{ copiedField === 'clientId' ? 'Copied' : 'Copy' }}
            </Button>
          </div>
        </div>

        <div class="flex flex-col gap-1.5">
          <Label>Console record id</Label>
          <p class="text-xs text-muted-foreground">
            Use this to look the client up in this console, or to issue a new secret later. This is NOT the same
            value as the client_id above.
          </p>
          <div class="flex items-center gap-2">
            <code id="client-credentials-record-id" class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all">
              {{ credentials.client.id }}
            </code>
            <Button
              id="client-credentials-copy-record-id"
              type="button"
              variant="outline"
              size="sm"
              @click="copy('recordId', credentials.client.id)"
            >
              {{ copiedField === 'recordId' ? 'Copied' : 'Copy' }}
            </Button>
          </div>
        </div>

        <div v-if="credentials.clientSecret" class="flex flex-col gap-1.5">
          <Label>client_secret</Label>
          <p class="text-xs text-muted-foreground">Shown once. It cannot be retrieved after you leave this page.</p>
          <div class="flex items-center gap-2">
            <code id="client-credentials-secret" class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all">
              {{ secretRevealed ? credentials.clientSecret : maskedSecret(credentials.clientSecret) }}
            </code>
            <Button
              id="client-credentials-reveal"
              type="button"
              variant="outline"
              size="sm"
              @click="secretRevealed = !secretRevealed"
            >
              {{ secretRevealed ? 'Hide' : 'Reveal' }}
            </Button>
            <Button
              id="client-credentials-copy-secret"
              type="button"
              variant="outline"
              size="sm"
              @click="copy('secret', credentials.clientSecret)"
            >
              {{ copiedField === 'secret' ? 'Copied' : 'Copy' }}
            </Button>
          </div>
          <Button id="client-credentials-download" type="button" variant="outline" class="self-start mt-1" @click="downloadCredentials">
            Download credentials (.json)
          </Button>
        </div>

        <p v-if="context === 'create'" class="text-sm text-muted-foreground border-t border-border pt-4">
          Registering this client also automatically created a resource server for it, with a default
          <code class="font-mono">read</code> scope — see the
          <RouterLink :to="{ name: 'tenant-admin-resource-servers', params: { slug } }" class="underline underline-offset-2">
            resource servers list
          </RouterLink>
          to find it.
        </p>
      </div>

      <div class="flex flex-col gap-4">
        <div class="flex items-start gap-2">
          <Checkbox
            id="client-credentials-ack"
            :model-value="acknowledged"
            class="mt-0.5"
            @update:model-value="(value) => (acknowledged = Boolean(value))"
          />
          <Label for="client-credentials-ack" class="font-normal leading-snug">
            I have saved these credentials. I understand the secret cannot be shown again.
          </Label>
        </div>
        <Button id="client-credentials-continue" :disabled="!canContinue" class="self-start" @click="handleContinue">
          Continue
        </Button>
      </div>
    </div>
  </div>
</template>
