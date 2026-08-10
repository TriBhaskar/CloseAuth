import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ClientCredentials } from '@/api/tenantAdminClients'

// Stage UI-3c: holds a just-issued client secret (from either create or
// regenerate) for exactly one hop — TenantClientsView.vue /
// TenantClientDetailView.vue sets it, then navigates to
// TenantClientCredentialsView.vue, which reads it and clears it once the
// admin acknowledges having saved it.
//
// Deliberately IN-MEMORY ONLY (a plain Pinia ref, no persistence plugin) —
// never localStorage/sessionStorage. Persisting a plaintext secret anywhere
// durable would defeat the entire point of the write-once ceremony: a
// secret is either shown right now, from this exact navigation, or it is
// genuinely gone (a page refresh, a bookmark, or a second visit after
// acknowledgement all correctly show "no longer available" — see that
// view's empty-store handling).
export type ClientCredentialsContext = 'create' | 'regenerate'

export const useTenantAdminClientCredentialsStore = defineStore('tenantAdminClientCredentials', () => {
  const credentials = ref<ClientCredentials | null>(null)
  const context = ref<ClientCredentialsContext | null>(null)

  function set(value: ClientCredentials, forContext: ClientCredentialsContext): void {
    credentials.value = value
    context.value = forContext
  }

  function clear(): void {
    credentials.value = null
    context.value = null
  }

  return { credentials, context, set, clear }
})
