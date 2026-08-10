// Stage UI-3d: the application-role tier — RS-scoped role CRUD, scope-bundle
// management, and assignment/held-names for a user — talking to
// internal/server/handlers_admin_application_roles.go's
// /t/{slug}/api/resource-servers/{rsId}/roles/** and
// /users/{userId}/application-roles/** routes.
//
// Two things this tier deliberately does NOT have, unlike tenantAdminRoles.ts:
//   - No isSystem gating. ApplicationRoleService.createApplicationRole
//     hardcodes is_system=false, and nothing else ever sets it true, so
//     ApplicationRoleView.isSystem is always false in practice — there is no
//     tenantRoleActions()-style gate here, and none should be added.
//   - No application_role.scope_rs_mismatch field mapping. Its errors map is
//     keyed by roleResourceServerId/scopeResourceServerId (RS UUIDs), not
//     form field names, so a field-level FormField binding would render raw
//     UUIDs. It's also structurally unreachable from this console: the scope
//     picker (TenantApplicationRoleDetailView.vue) only ever offers scopes
//     from listScopes(slug, rsId, ...) — the role's OWN resource server —
//     so a caller here should treat the error defensively (a written
//     explanation, never a form-field binding) rather than map it.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'
import type { PageView } from '@/api/tenantAdminUsers'
import type { ScopeView } from '@/api/tenantAdminResourceServers'

// Mirrors rbac/dto/ApplicationRoleView.java exactly. Jackson serializes the
// record's isDefault()/isSystem() accessors as "isDefault"/"isSystem" — see
// tenantAdminRoles.ts's TenantRoleView for the same convention.
export interface ApplicationRoleView {
  id: string
  resourceServerId: string
  tenantId: string
  name: string
  description: string | null
  isDefault: boolean
  isSystem: boolean
  createdAt: string
  updatedAt: string
}

export const DEFAULT_APPLICATION_ROLE_PAGE_SIZE = 20

export async function listApplicationRoles(
  slug: string,
  rsId: string,
  page: number,
  size: number = DEFAULT_APPLICATION_ROLE_PAGE_SIZE,
): Promise<AdminResult<PageView<ApplicationRoleView>>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}/roles?page=${page}&size=${size}`)
  return parseAdminResult<PageView<ApplicationRoleView>>(result)
}

// Mirrors rbac/dto/CreateApplicationRoleCommand.java.
export interface CreateApplicationRolePayload {
  name: string
  description?: string
  isDefault: boolean
}

// Mirrors rbac/dto/UpdateApplicationRoleCommand.java — name is deliberately
// absent (immutable). Full replacement of the two mutable fields, exactly
// like UpdateTenantRolePayload — always send both, pre-populated.
export interface UpdateApplicationRolePayload {
  description?: string
  isDefault: boolean
}

export async function createApplicationRole(
  slug: string,
  rsId: string,
  payload: CreateApplicationRolePayload,
): Promise<AdminResult<ApplicationRoleView>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}/roles`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<ApplicationRoleView>(result)
}

export async function getApplicationRole(slug: string, rsId: string, roleId: string): Promise<AdminResult<ApplicationRoleView>> {
  const result = await tenantAdminFetch(slug, `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}`)
  return parseAdminResult<ApplicationRoleView>(result)
}

export async function updateApplicationRole(
  slug: string,
  rsId: string,
  roleId: string,
  payload: UpdateApplicationRolePayload,
): Promise<AdminResult<ApplicationRoleView>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}`,
    { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) },
  )
  return parseAdminResult<ApplicationRoleView>(result)
}

/**
 * Deleting a role cascades: every user holding it loses it immediately
 * (ON DELETE CASCADE on user_application_roles.application_role_id), with no
 * count returned — same caveat as deleteRole in tenantAdminRoles.ts.
 */
export async function deleteApplicationRole(slug: string, rsId: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

// ---- scope bundle -------------------------------------------------------

export const DEFAULT_ROLE_SCOPES_PAGE_SIZE = 100

/** Fetched at a large fixed size — the scope-bundle checkbox list is not paged, same rationale as listRoles' catalog fetch. */
export async function listRoleScopes(
  slug: string,
  rsId: string,
  roleId: string,
  size: number = DEFAULT_ROLE_SCOPES_PAGE_SIZE,
): Promise<AdminResult<PageView<ScopeView>>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}/scopes?page=0&size=${size}`,
  )
  return parseAdminResult<PageView<ScopeView>>(result)
}

/** Idempotent on the backend (already-bundled is a no-op 204). */
export async function addScopeToRole(slug: string, rsId: string, roleId: string, scopeId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}/scopes/${encodeURIComponent(scopeId)}`,
    { method: 'POST' },
  )
  return parseAdminResult<void>(result)
}

/** Idempotent on the backend (not-bundled is a no-op 204). */
export async function removeScopeFromRole(slug: string, rsId: string, roleId: string, scopeId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/resource-servers/${encodeURIComponent(rsId)}/roles/${encodeURIComponent(roleId)}/scopes/${encodeURIComponent(scopeId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

// ---- user assignment ------------------------------------------------------

/**
 * Role NAMES currently held by userId, WITHIN one resource server
 * (ApplicationRoleController.applicationRolesForUser — the UI-3d backend
 * addition). Deliberately RS-scoped, unlike getHeldRoleNames in
 * tenantAdminRoles.ts: application-role names are unique only per resource
 * server (uq_application_roles_rs_name), so a tenant-wide name list would be
 * unjoinable — two RSes in one tenant can share a role name.
 */
export async function getHeldApplicationRoleNames(slug: string, userId: string, rsId: string): Promise<AdminResult<string[]>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/application-roles?resourceServerId=${encodeURIComponent(rsId)}`,
  )
  return parseAdminResult<string[]>(result)
}

/** Idempotent on the backend (already-held is a no-op 204). No last-admin-style guard at this tier. */
export async function assignApplicationRole(slug: string, userId: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/application-roles/${encodeURIComponent(roleId)}`,
    { method: 'POST' },
  )
  return parseAdminResult<void>(result)
}

/** Idempotent on the backend (not-held is a no-op 204). */
export async function revokeApplicationRole(slug: string, userId: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/application-roles/${encodeURIComponent(roleId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

// ---- join: held role names -> catalog ids ---------------------------------

export type HeldApplicationRole =
  | { kind: 'joined'; name: string; roleId: string }
  | { kind: 'unresolved'; name: string }

/**
 * Joins the names getHeldApplicationRoleNames returns against the id-bearing
 * catalog from listApplicationRoles for the SAME resource server — unambiguous
 * because names are unique per RS. A held name absent from the catalog (a
 * truncated catalog beyond DEFAULT_ROLE_SCOPES_PAGE_SIZE-equivalent paging, or
 * a role deleted/renamed concurrently) is 'unresolved', never dropped — the
 * same honesty rule as tenantAdminRoles.ts's joinHeldRoles.
 */
export function joinHeldApplicationRoles(heldNames: string[], catalog: ApplicationRoleView[]): HeldApplicationRole[] {
  const idByName = new Map(catalog.map((role) => [role.name, role.id]))
  return heldNames.map((name) => {
    const roleId = idByName.get(name)
    return roleId ? { kind: 'joined', name, roleId } : { kind: 'unresolved', name }
  })
}

// ---- domain-truth helpers ----------------------------------------------

/** application_role.name_conflict is unique per RS (uq_application_roles_rs_name), not per tenant. */
export const APPLICATION_ROLE_CONFLICT_FIELDS: Record<string, string> = {
  'application_role.name_conflict': 'name',
}
