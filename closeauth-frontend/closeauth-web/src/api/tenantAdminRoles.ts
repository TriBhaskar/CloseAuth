// Stage UI-3b: the tenant-admin console's tenant-role catalog + assignment
// API layer, talking to internal/server/handlers_admin_users.go's
// /t/{slug}/api/roles and /users/{userId}/tenant-roles/** routes.
//
// Stage UI-3d adds the CRUD half — create/get/update/delete — talking to the
// new handlers_admin_tenant_roles.go handlers on the same /roles path. GET
// (paged) already existed as listRoles below (fetched at
// ROLE_CATALOG_PAGE_SIZE for the assignment panels); listRolesPaged is the
// UI-3d addition for a real paged list view.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'
import type { PageView } from '@/api/tenantAdminUsers'

// Mirrors rbac/dto/TenantRoleView.java exactly. Jackson serializes the
// record's isDefault()/isSystem() accessors as "isDefault"/"isSystem".
export interface TenantRoleView {
  id: string
  tenantId: string
  name: string
  description: string | null
  isDefault: boolean
  isSystem: boolean
  createdAt: string
  updatedAt: string
}

// PageView.MAX_SIZE — the clamp ceiling. Fetching the whole catalog in one
// page means the role-assignment panel needs no paging UI of its own; if a
// tenant's catalog exceeds this, listRoles' totalPages > 1 tells the caller
// to show a visible truncation notice instead of silently acting on a
// partial catalog.
export const ROLE_CATALOG_PAGE_SIZE = 100

export async function listRoles(slug: string): Promise<AdminResult<PageView<TenantRoleView>>> {
  const result = await tenantAdminFetch(slug, `/roles?page=0&size=${ROLE_CATALOG_PAGE_SIZE}`)
  return parseAdminResult<PageView<TenantRoleView>>(result)
}

export const DEFAULT_ROLE_PAGE_SIZE = 20

/** UI-3d: a real paged list for TenantRolesView, distinct from listRoles' fixed size=100 catalog fetch. */
export async function listRolesPaged(
  slug: string,
  page: number,
  size: number = DEFAULT_ROLE_PAGE_SIZE,
): Promise<AdminResult<PageView<TenantRoleView>>> {
  const result = await tenantAdminFetch(slug, `/roles?page=${page}&size=${size}`)
  return parseAdminResult<PageView<TenantRoleView>>(result)
}

// Mirrors rbac/dto/CreateTenantRoleCommand.java.
export interface CreateTenantRolePayload {
  name: string
  description?: string
  isDefault: boolean
}

// Mirrors rbac/dto/UpdateTenantRoleCommand.java — name is deliberately
// absent (immutable). This is a FULL REPLACEMENT of the two mutable fields
// (TenantRoleService.updateTenantRole sets both unconditionally, and
// isDefault is a primitive boolean — an omitted value deserializes to
// false): a caller must always send both, pre-populated from the current
// values, never a partial patch.
export interface UpdateTenantRolePayload {
  description?: string
  isDefault: boolean
}

export async function createRole(slug: string, payload: CreateTenantRolePayload): Promise<AdminResult<TenantRoleView>> {
  const result = await tenantAdminFetch(slug, '/roles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<TenantRoleView>(result)
}

export async function getRole(slug: string, roleId: string): Promise<AdminResult<TenantRoleView>> {
  const result = await tenantAdminFetch(slug, `/roles/${encodeURIComponent(roleId)}`)
  return parseAdminResult<TenantRoleView>(result)
}

export async function updateRole(
  slug: string,
  roleId: string,
  payload: UpdateTenantRolePayload,
): Promise<AdminResult<TenantRoleView>> {
  const result = await tenantAdminFetch(slug, `/roles/${encodeURIComponent(roleId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<TenantRoleView>(result)
}

/**
 * Deleting a role cascades: every user holding it loses it immediately
 * (ON DELETE CASCADE on user_tenant_roles.tenant_role_id), with no count
 * returned — the caller's confirm copy must say so, since CloseAuth cannot
 * report how many users are affected.
 */
export async function deleteRole(slug: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/roles/${encodeURIComponent(roleId)}`, { method: 'DELETE' })
  return parseAdminResult<void>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/** tenant_role.name_conflict is the only name-shaped conflict at this tier (uq_tenant_roles_tenant_name). */
export const TENANT_ROLE_CONFLICT_FIELDS: Record<string, string> = {
  'tenant_role.name_conflict': 'name',
}

export type TenantRoleAction = 'edit' | 'delete'

/**
 * System roles (TENANT_ADMIN, TENANT_MEMBER, BILLING_ADMIN — the starter
 * pack) are immutable: update/delete both throw SystemRoleModificationException
 * (403 role.system_immutable). Never offer edit/delete for one — the row
 * renders "(system — cannot be changed)" instead. Assignment/revocation are
 * unaffected (that guard is on the role DEFINITION, not its assignments) and
 * stay available on the user-detail view regardless of this list.
 */
export function tenantRoleActions(role: TenantRoleView): TenantRoleAction[] {
  return role.isSystem ? [] : ['edit', 'delete']
}

/** Role NAMES currently held by userId (TenantRoleController.rolesForUser — the UI-3b backend addition). */
export async function getHeldRoleNames(slug: string, userId: string): Promise<AdminResult<string[]>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}/tenant-roles`)
  return parseAdminResult<string[]>(result)
}

/** Idempotent on the backend (already-held is a no-op 204, not a 409). */
export async function assignRole(slug: string, userId: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/tenant-roles/${encodeURIComponent(roleId)}`,
    { method: 'POST' },
  )
  return parseAdminResult<void>(result)
}

/**
 * Idempotent on the backend when the role isn't held, but revoking
 * TENANT_ADMIN from the tenant's last ACTIVE holder is refused with 409
 * "tenant_role.last_admin" — parseAdminResult surfaces that as
 * `{kind:'conflict', code:'tenant_role.last_admin', ...}`, not a generic
 * conflict.
 */
export async function revokeRole(slug: string, userId: string, roleId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/tenant-roles/${encodeURIComponent(roleId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

// ---- join: held role names -> catalog ids ---------------------------------

export type HeldRole = { kind: 'joined'; name: string; roleId: string } | { kind: 'unresolved'; name: string }

/**
 * Joins the names GET /tenant-roles returns against the id-bearing catalog
 * from listRoles — assign/revoke need a role id, the held-roles endpoint
 * only returns names. A held name absent from the catalog (a truncated
 * >100-role catalog, or a role deleted/renamed concurrently) is surfaced as
 * 'unresolved', never dropped: silently omitting it would render a user as
 * NOT holding a role they actually hold, which is exactly the fake-data
 * failure this stage exists to forbid. An 'unresolved' entry has no id, so
 * it can't be toggled from this panel — the caller renders it disabled with
 * an explanation, not hidden.
 */
export function joinHeldRoles(heldNames: string[], catalog: TenantRoleView[]): HeldRole[] {
  const idByName = new Map(catalog.map((role) => [role.name, role.id]))
  return heldNames.map((name) => {
    const roleId = idByName.get(name)
    return roleId ? { kind: 'joined', name, roleId } : { kind: 'unresolved', name }
  })
}
