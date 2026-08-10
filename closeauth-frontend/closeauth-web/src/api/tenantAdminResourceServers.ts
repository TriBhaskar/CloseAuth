// Stage UI-3c: resource servers + their scope catalog — the API layer
// talking to internal/server/handlers_admin_resource_servers.go's
// /t/{slug}/api/resource-servers/** surface. Unlike clients, the backend
// exposes full CRUD here, so this module is a plain, complete wrapper.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'
import type { PageView } from '@/api/tenantAdminUsers'

// Mirrors resourceserver/dto/ResourceServerView.java exactly. Scopes are
// fetched separately (listScopes) — this view never embeds them.
export interface ResourceServerView {
  id: string
  tenantId: string
  slug: string
  name: string
  audienceIdentifier: string
  autoCreated: boolean
  createdAt: string
  updatedAt: string | null
}

// Mirrors resourceserver/dto/ScopeView.java exactly.
export interface ScopeView {
  id: string
  resourceServerId: string
  scopeName: string
  description: string | null
  isDefault: boolean
  requiresConsent: boolean
  createdAt: string
}

export const DEFAULT_PAGE_SIZE = 20

// Mirrors resourceserver/dto/CreateResourceServerCommand.java.
export interface CreateResourceServerPayload {
  slug: string
  name: string
  audienceIdentifier: string
}

// Mirrors resourceserver/dto/UpdateResourceServerCommand.java —
// audienceIdentifier is deliberately absent: it is immutable after
// creation. Sending one anyway would not error, just be silently ignored
// (structural immutability, not a rejected write) — so the detail view
// never offers an editable audience field at all.
export interface UpdateResourceServerPayload {
  name: string
  slug: string
}

// Mirrors resourceserver/dto/AddScopeCommand.java.
export interface AddScopePayload {
  scopeName: string
  description?: string
  isDefault: boolean
  requiresConsent: boolean
}

// Mirrors resourceserver/dto/UpdateScopeCommand.java — scopeName is
// deliberately absent (immutable, silently ignored if sent). This is a FULL
// REPLACEMENT of the three mutable fields, not a sparse merge
// (ResourceServerService.updateScope sets all three unconditionally): a
// caller must always send description/isDefault/requiresConsent together,
// pre-populated from the current values, never a partial patch.
export interface UpdateScopePayload {
  description?: string
  isDefault: boolean
  requiresConsent: boolean
}

export async function listResourceServers(
  slug: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<AdminResult<PageView<ResourceServerView>>> {
  const result = await tenantAdminFetch(slug, `/resource-servers?page=${page}&size=${size}`)
  return parseAdminResult<PageView<ResourceServerView>>(result)
}

export async function createResourceServer(
  slug: string,
  payload: CreateResourceServerPayload,
): Promise<AdminResult<ResourceServerView>> {
  const result = await tenantAdminFetch(slug, '/resource-servers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ResourceServerView>(result)
}

export async function getResourceServer(slug: string, rsId: string): Promise<AdminResult<ResourceServerView>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}`)
  return parseAdminResult<ResourceServerView>(result)
}

export async function updateResourceServer(
  slug: string,
  rsId: string,
  payload: UpdateResourceServerPayload,
): Promise<AdminResult<ResourceServerView>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ResourceServerView>(result)
}

/** 409 resource_server.deletion_not_allowed for an auto-created RS — gate the triggering control on `!rs.autoCreated`. */
export async function deleteResourceServer(slug: string, rsId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}`, { method: 'DELETE' })
  return parseAdminResult<void>(result)
}

export async function listScopes(
  slug: string,
  rsId: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<AdminResult<PageView<ScopeView>>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/scopes?page=${page}&size=${size}`,
  )
  return parseAdminResult<PageView<ScopeView>>(result)
}

export async function addScope(
  slug: string,
  rsId: string,
  payload: AddScopePayload,
): Promise<AdminResult<ScopeView>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}/scopes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ScopeView>(result)
}

export async function updateScope(
  slug: string,
  rsId: string,
  scopeId: string,
  payload: UpdateScopePayload,
): Promise<AdminResult<ScopeView>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/scopes/${encodeURIComponent(scopeId)}`,
    { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) },
  )
  return parseAdminResult<ScopeView>(result)
}

export async function deleteScope(slug: string, rsId: string, scopeId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/scopes/${encodeURIComponent(scopeId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/**
 * The three resource-server conflict codes arrive as a 409 with a `code`
 * (parseAdminResult's `conflict` kind), not the 400 `errors` map — this maps
 * each onto the form field it actually concerns, so a create/rename form can
 * land the message on the right control instead of falling back to a
 * generic banner (the "field-level validation errors against fields, not
 * banners" house rule extended to conflicts).
 */
export const RESOURCE_SERVER_CONFLICT_FIELDS: Record<string, string> = {
  'resource_server.slug_conflict': 'slug',
  'resource_server.audience_conflict': 'audienceIdentifier',
  'resource_server.scope_conflict': 'scopeName',
}

export type ResourceServerAction = 'edit' | 'delete'

/**
 * An auto-created RS's lifecycle is tied 1:1 to its client
 * (ResourceServerService.deleteResourceServer refuses direct deletion with
 * 409 resource_server.deletion_not_allowed) — never offer the delete control
 * for one; name/slug stay editable regardless.
 */
export function resourceServerActions(rs: ResourceServerView): ResourceServerAction[] {
  return rs.autoCreated ? ['edit'] : ['edit', 'delete']
}
