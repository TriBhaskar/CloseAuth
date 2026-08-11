// Stage UI-4b: the platform console's tenant-onboarding API layer, talking
// to internal/server/handlers_platform_onboarding.go's
// /platform/api/tenants/{tenantId}/{users,bootstrap-admin,reissue-onboarding-credential}
// surface. Built on platformAdminFetch/parsePlatformResult, same as
// platformAdminTenants.ts — never tenantAdminFetch, there is no session
// tenant id on this surface to scope onto.
import { platformAdminFetch } from '@/api/platformAdminClient'
import { parsePlatformResult } from '@/api/platformAdminProblem'
import type { AdminResult } from '@/api/tenantAdminProblem'
import { DEFAULT_PAGE_SIZE, type PageView } from '@/api/platformAdminTenants'

// Mirrors identity/enums/UserStatus.java exactly.
export type TenantUserStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'DELETED'

// Mirrors identity/dto/UserView.java exactly.
export interface TenantUserView {
  id: string
  tenantId: string
  email: string
  emailVerified: boolean
  phone: string | null
  phoneVerified: boolean
  firstName: string | null
  lastName: string | null
  status: TenantUserStatus
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
}

// Mirrors auth/dto/BootstrapAdminCommand.java exactly. Deliberately carries
// NO password field — the temporary password is always server-generated
// (decision 6), never caller-supplied.
export interface BootstrapAdminPayload {
  email: string
  firstName?: string
  lastName?: string
}

// Mirrors auth/dto/TenantAdminBootstrappedView.java exactly. temporaryPassword
// is the ONLY place the raw value ever appears (write-once) — never persist
// it, never log it, clear it from memory once the operator dismisses the
// panel that shows it.
export interface TenantAdminBootstrappedView {
  user: TenantUserView
  temporaryPassword: string
  temporaryPasswordExpiresAt: string
}

// Mirrors auth/dto/TempCredentialReissuedView.java exactly. Note the shape
// differs from bootstrap's: a bare userId, not a nested user object — same
// one-time temporaryPassword field name.
export interface TempCredentialReissuedView {
  userId: string
  temporaryPassword: string
  temporaryPasswordExpiresAt: string
}

/**
 * Lists a tenant's users — reaches TenantUserController (/v1/tenants/{id}/users),
 * NOT PlatformTenantController, via handlePlatformTenantUsersList. Exists
 * solely so the console can find a userId to reissue a credential for; the
 * tenant list itself carries no user data. A platform-admin token is granted
 * access via AdminAuthorization.hasTenantAccess (platform admins transcend
 * tenant scoping), so this is not a gate bypass.
 */
export async function listTenantUsers(
  tenantId: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<AdminResult<PageView<TenantUserView>>> {
  const result = await platformAdminFetch(`/tenants/${encodeURIComponent(tenantId)}/users?page=${page}&size=${size}`)
  return parsePlatformResult<PageView<TenantUserView>>(result)
}

/**
 * Creates the tenant's first admin atomically: an ACTIVE user, a granted
 * TENANT_ADMIN role, and an emailed onboarding link — one transaction on the
 * backend. Requires the tenant to be ACTIVE; a PROVISIONING or SUSPENDED
 * tenant is refused with 403 tenant.not_active (a domain error, not an
 * authz denial — the platform session survives it, see
 * platform_api_result.go). Refused with 409 tenant_onboarding.
 * admin_already_exists if the tenant already has one.
 */
export async function bootstrapTenantAdmin(
  tenantId: string,
  payload: BootstrapAdminPayload,
): Promise<AdminResult<TenantAdminBootstrappedView>> {
  const result = await platformAdminFetch(`/tenants/${encodeURIComponent(tenantId)}/bootstrap-admin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parsePlatformResult<TenantAdminBootstrappedView>(result)
}

/**
 * Regenerates a fresh temporary credential for an admin whose onboarding
 * link was never used — a recovery tool, not a way to force a working
 * account back into rotation. Takes no body: the backend re-sends to the
 * user's EXISTING email and cannot correct a typo'd address. Refused with
 * 409 tenant_onboarding.no_pending_temp_credential if the admin already set
 * their own password (or never had a local-password identity at all — the
 * backend does not distinguish the two).
 */
export async function reissueOnboardingCredential(
  tenantId: string,
  userId: string,
): Promise<AdminResult<TempCredentialReissuedView>> {
  const result = await platformAdminFetch(
    `/tenants/${encodeURIComponent(tenantId)}/users/${encodeURIComponent(userId)}/reissue-onboarding-credential`,
    { method: 'POST' },
  )
  return parsePlatformResult<TempCredentialReissuedView>(result)
}
