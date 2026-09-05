// Stage UI-3c: the tenant-admin console's client-registration API layer,
// talking to internal/server/handlers_admin_clients.go's
// /t/{slug}/api/clients/** surface. Built on tenantAdminFetch
// (tenantAdminClient.ts) and parseAdminResult (problem.ts), same
// as tenantAdminUsers.ts.
//
// FE-4.10: listClients closes the list gap this module used to flag —
// TenantClientController now exposes a tenant-scoped GET, same
// page/size-paged shape as listResourceServers.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'
import type { PageView } from '@/api/tenantAdminUsers'

// Mirrors client/dto/ClientView.java exactly. Never carries a secret.
// publicClient (UI-3c) is what tells the detail view whether "regenerate
// secret" is even a meaningful action to offer — a public client has none.
//
// FE-4c additions: postLogoutRedirectUris, createdAt (free — SAS already
// tracks client_id_issued_at), secretRotatedAt (null until the first
// regenerateClientSecret call — see CloseAuthClientSettings.java).
//
// Client update/delete additions: requireProofKey/trusted seed the edit
// form (both already tracked by SAS, just not previously surfaced);
// platformManaged is true for the tenant's auto-provisioned
// admin-console-{slug} client — updateClient/deleteClient both 409
// (client.platform_managed) on it, so the console must hide both controls
// rather than let an admin discover the refusal by clicking (see
// clientActions below).
export interface ClientView {
  id: string
  clientId: string
  clientName: string
  tenantId: string
  publicClient: boolean
  grantTypes: string[]
  scopes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  createdAt: string
  secretRotatedAt: string | null
  requireProofKey: boolean
  trusted: boolean
  platformManaged: boolean
}

// Mirrors client/dto/ClientCreatedView.java and client/dto/ClientSecretView.java
// — deliberately wire-identical (see ClientSecretView.java's javadoc), so
// both the create and regenerate responses decode through this one type and
// render through one shared credential-display component
// (TenantClientCredentialsView.vue). clientSecret is null only for a public
// client's create response — regenerate never returns null (a public
// client's regenerate attempt is refused with 409 before any response body
// like this is produced).
export interface ClientCredentials {
  client: ClientView
  clientSecret: string | null
}

// Mirrors client/dto/RegisterClientCommand.java exactly. UI-3c: no
// clientSecret field exists here at all — the backend generates it
// server-side and ignores any caller-supplied value (ClientSecretGenerator).
// publicClient defaults to false server-side if omitted, but this SPA always
// sends it explicitly — the create form has no "unset" state.
//
// FE-4c: postLogoutUris added (optional, same laxity as redirectUris — no
// frontend-independent format validation happens server-side; the wizard is
// responsible for shape checks before submission).
//
// Post-FE-4c: clientId dropped too — the backend now derives the OAuth2
// client_id from clientName (ClientIdGenerator), the same "nothing for an
// operator to usefully type" reasoning as the secret above. The generated
// value comes back on ClientView.clientId in the create response.
export interface RegisterClientPayload {
  clientName: string
  publicClient: boolean
  grantTypes: string[]
  scopes?: string[]
  redirectUris?: string[]
  postLogoutUris?: string[]
  requireProofKey: boolean
  trusted: boolean
}

export const DEFAULT_PAGE_SIZE = 20

export async function listClients(
  slug: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<AdminResult<PageView<ClientView>>> {
  const result = await tenantAdminFetch(slug, `/clients?page=${page}&size=${size}`)
  return parseAdminResult<PageView<ClientView>>(result)
}

export async function registerClient(
  slug: string,
  payload: RegisterClientPayload,
): Promise<AdminResult<ClientCredentials>> {
  const result = await tenantAdminFetch(slug, '/clients', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ClientCredentials>(result)
}

// {clientId} here is the SAS internal record id (RegisteredClient.getId()),
// NOT the OAuth2 client_id an application would present at token time — a
// distinction easy to get wrong, which is why the credential-handoff view
// labels both separately rather than assuming an admin will remember which
// is which.
export async function getClient(slug: string, recordId: string): Promise<AdminResult<ClientView>> {
  const result = await tenantAdminFetch(slug, `/clients/${encodeURIComponent(recordId)}`)
  return parseAdminResult<ClientView>(result)
}

// Mirrors client/dto/UpdateClientCommand.java exactly — a FULL REPLACEMENT
// of the mutable field set, not a sparse merge, same convention as
// UpdateResourceServerPayload (tenantAdminResourceServers.ts): a caller
// always sends every field pre-populated from the current values. clientId,
// tenantId, publicClient, and grantTypes have no field here at all — they
// are structurally immutable, so there is nothing to send and nothing that
// could be silently changed.
export interface UpdateClientPayload {
  clientName: string
  scopes: string[]
  redirectUris: string[]
  postLogoutUris: string[]
  requireProofKey: boolean
  trusted: boolean
}

export async function updateClient(
  slug: string,
  recordId: string,
  payload: UpdateClientPayload,
): Promise<AdminResult<ClientView>> {
  const result = await tenantAdminFetch(slug, `/clients/${encodeURIComponent(recordId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ClientView>(result)
}

/**
 * Hard-deletes the client and its 1:1 auto-created resource server. 409
 * "client.platform_managed" for the tenant's admin-console client — gate the
 * triggering control on `clientActions(client).includes('delete')` rather
 * than relying solely on this rejection.
 */
export async function deleteClient(slug: string, recordId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/clients/${encodeURIComponent(recordId)}`, { method: 'DELETE' })
  return parseAdminResult<void>(result)
}

// FE-4d: the console overview's Clients tile — the smallest possible slice
// of the still-blocked full-list gap (a number, no rows, no secrets).
export interface ClientCountView {
  count: number
}

export async function getClientCount(slug: string): Promise<AdminResult<ClientCountView>> {
  const result = await tenantAdminFetch(slug, '/clients/count')
  return parseAdminResult<ClientCountView>(result)
}

/**
 * Mints a fresh secret, returned once — the missing recovery path this stage
 * adds (previously a lost secret meant a permanently dead client). Refused
 * with 409 "client.public_no_secret" for a public client; callers should
 * gate the triggering control on `!client.publicClient` rather than relying
 * solely on this rejection (never offer an action the backend would refuse).
 */
export async function regenerateClientSecret(
  slug: string,
  recordId: string,
): Promise<AdminResult<ClientCredentials>> {
  const result = await tenantAdminFetch(slug, `/clients/${encodeURIComponent(recordId)}/client-secret`, {
    method: 'POST',
  })
  return parseAdminResult<ClientCredentials>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/**
 * The one client conflict code that lands on a specific field today
 * (client.public_no_secret concerns the whole rotate action, not a form
 * field, so it is not listed here — it is handled as a banner where it's
 * raised). Same house shape as RESOURCE_SERVER_CONFLICT_FIELDS
 * (tenantAdminResourceServers.ts): a 409 code -> the field an edit form
 * should land the message on, falling back to a banner for anything absent
 * from this map.
 */
export const CLIENT_CONFLICT_FIELDS: Record<string, string> = {}

export type ClientAction = 'edit' | 'delete' | 'rotate'

/**
 * The tenant's auto-provisioned admin-console client is what the console
 * itself authenticates with — editing its redirect URI or deleting it would
 * strand the tenant's admins with no self-service recovery, so neither
 * control is ever offered for it (the backend 409s "client.platform_managed"
 * on both, and this is the "never offer an action the backend would refuse"
 * gate for that). rotate is additionally withheld from a public client
 * (which has no secret to rotate) — this mirrors, in one place, the
 * `!client.publicClient` check the Credentials tab already applied inline.
 */
export function clientActions(client: ClientView): ClientAction[] {
  if (client.platformManaged) {
    return []
  }
  return client.publicClient ? ['edit', 'delete'] : ['edit', 'delete', 'rotate']
}
