// Stage UI-4: the platform console's platform-admin CRUD/roles API layer,
// talking to internal/server/handlers_platform_admins.go's
// /platform/api/admins/** surface.
import { platformAdminFetch } from '@/api/platformAdminClient'
import { parsePlatformResult } from '@/api/platformAdminProblem'
import type { AdminResult } from '@/api/tenantAdminProblem'
import type { PageView } from '@/api/platformAdminTenants'

// Mirrors platform/enums/PlatformAdminStatus.java exactly.
export type PlatformAdminStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED'

// Mirrors platform/dto/PlatformAdminView.java exactly. Never carries
// credential material (no hash/algo) and carries no roles — roles are a
// separate read (getAdminRoles, below), the UI-4 backend addition.
export interface PlatformAdminView {
  id: string
  email: string
  status: PlatformAdminStatus
  firstName: string | null
  lastName: string | null
  lastLoginAt: string | null
  createdAt: string
}

// The two platform roles V2__seed_platform_roles.sql seeds. There is no
// platform-role catalog endpoint to read this list from dynamically (and
// none is planned — this stage's scope discipline); that migration is the
// source of truth, mirrored here as a constant, same as
// internal/server/handlers_platform_admins.go's validPlatformRole allow-list
// on the Go side.
export const PLATFORM_ROLES = ['PLATFORM_ADMIN', 'PLATFORM_SUPPORT'] as const
export type PlatformRoleName = (typeof PLATFORM_ROLES)[number]

export interface CreatePlatformAdminPayload {
  email: string
  password: string
  firstName?: string
  lastName?: string
}

export async function listAdmins(
  page: number,
  size = 20,
): Promise<AdminResult<PageView<PlatformAdminView>>> {
  const result = await platformAdminFetch(`/admins?page=${page}&size=${size}`)
  return parsePlatformResult<PageView<PlatformAdminView>>(result)
}

export async function createAdmin(payload: CreatePlatformAdminPayload): Promise<AdminResult<PlatformAdminView>> {
  const result = await platformAdminFetch('/admins', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parsePlatformResult<PlatformAdminView>(result)
}

async function lifecycleAction(adminId: string, action: 'suspend' | 'activate'): Promise<AdminResult<PlatformAdminView>> {
  const result = await platformAdminFetch(`/admins/${encodeURIComponent(adminId)}/${action}`, { method: 'POST' })
  return parsePlatformResult<PlatformAdminView>(result)
}

/**
 * ACTIVE -> SUSPENDED. Writes the platform-admin revocation marker: the
 * admin's live token is revoked within seconds (checked per-request by
 * PlatformAdminRevocationTokenValidator), not at its 5-minute expiry. Refused
 * with 409 platform_admin.last_admin if this would leave zero active
 * PLATFORM_ADMIN holders.
 */
export function suspendAdmin(adminId: string): Promise<AdminResult<PlatformAdminView>> {
  return lifecycleAction(adminId, 'suspend')
}

/** SUSPENDED -> ACTIVE. */
export function activateAdmin(adminId: string): Promise<AdminResult<PlatformAdminView>> {
  return lifecycleAction(adminId, 'activate')
}

/** GET .../roles — the platform-role names this admin currently holds (empty for a freshly created admin). */
export async function getAdminRoles(adminId: string): Promise<AdminResult<string[]>> {
  const result = await platformAdminFetch(`/admins/${encodeURIComponent(adminId)}/roles`)
  return parsePlatformResult<string[]>(result)
}

/**
 * Assigns roleName to adminId. Idempotent on the backend (assigning an
 * already-held role is a no-op, not an error).
 */
export async function assignRole(adminId: string, roleName: PlatformRoleName): Promise<AdminResult<void>> {
  const result = await platformAdminFetch(`/admins/${encodeURIComponent(adminId)}/roles/${roleName}`, { method: 'POST' })
  return parsePlatformResult<void>(result)
}

/**
 * Revokes roleName from adminId. Revoking PLATFORM_ADMIN from the sole
 * active holder is refused with 409 platform_admin.last_admin.
 */
export async function revokeRole(adminId: string, roleName: PlatformRoleName): Promise<AdminResult<void>> {
  const result = await platformAdminFetch(`/admins/${encodeURIComponent(adminId)}/roles/${roleName}`, { method: 'DELETE' })
  return parsePlatformResult<void>(result)
}
