// Stage UI-3c: the tenant-admin console's client-registration API layer,
// talking to internal/server/handlers_admin_clients.go's
// /t/{slug}/api/clients/** surface. Built on tenantAdminFetch
// (tenantAdminClient.ts) and parseAdminResult (tenantAdminProblem.ts), same
// as tenantAdminUsers.ts.
//
// No listClients: the backend has none (TenantClientController implements
// only create + get — SAS's RegisteredClientRepository exposes no
// tenant-scoped list/delete; flagged, not silently built around). See
// TenantClientsView.vue for how the surface handles that honestly.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'

// Mirrors client/dto/ClientView.java exactly. Never carries a secret.
// publicClient (UI-3c) is what tells the detail view whether "regenerate
// secret" is even a meaningful action to offer — a public client has none.
export interface ClientView {
  id: string
  clientId: string
  clientName: string
  tenantId: string
  publicClient: boolean
  grantTypes: string[]
  scopes: string[]
  redirectUris: string[]
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
export interface RegisterClientPayload {
  clientId: string
  clientName: string
  publicClient: boolean
  grantTypes: string[]
  scopes?: string[]
  redirectUris?: string[]
  requireProofKey: boolean
  trusted: boolean
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
