// FE-4d: the tenant-console's self-service surface, talking to
// internal/server/handlers_me_proxy.go's /t/{slug}/api/me/** surface. Built
// on tenantAdminFetch/parseAdminResult, same house pattern as every other
// module here. Reachable by every tenant user with a live session, admin or
// not (RequireTenantSession, not RequireAdminSession) — see guards.ts's
// requiresTenantSession.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'
import type { UserView } from '@/api/tenantAdminUsers'

// MeController.me()'s tenant-user response is UserService.getUserById(...)
// — byte-identical to the admin-facing UserView (tenantAdminUsers.ts), so
// this reuses that type directly rather than declaring a parallel,
// easy-to-drift duplicate.
export type MeProfileView = UserView

// Mirrors session/dto/MeSessionView.java exactly — same shape as
// tenantAdminUserSessions.ts's AdminSessionView (the admin-facing
// equivalent), deliberately OMITS the session key.
export interface MySessionView {
  id: string
  rememberMe: boolean
  ipAddress: string | null
  userAgent: string | null
  amr: string | null
  createdAt: string
  idleExpiresAt: string
  absoluteExpiresAt: string
  lastAccessedAt: string
}

export interface ChangePasswordPayload {
  currentPassword: string
  newPassword: string
}

/** GET /v1/me — the caller's own profile. Read-only (no PATCH endpoint exists yet). */
export async function getMe(slug: string): Promise<AdminResult<MeProfileView>> {
  const result = await tenantAdminFetch(slug, '/me')
  return parseAdminResult<MeProfileView>(result)
}

/** GET /v1/me/sessions — every live session belonging to the caller, in THIS tenant. */
export async function listMySessions(slug: string): Promise<AdminResult<MySessionView[]>> {
  const result = await tenantAdminFetch(slug, '/me/sessions')
  return parseAdminResult<MySessionView[]>(result)
}

/**
 * DELETE /v1/me/sessions/{id} — revokes exactly one of the caller's own
 * sessions (the 4-leg cascade). Strictly self-scoped server-side; there is
 * no way to reach another principal's session through this endpoint.
 */
export async function revokeMySession(slug: string, sessionId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/me/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
  return parseAdminResult<void>(result)
}

/**
 * POST /v1/me/change-password — verifies the current password, sets the
 * new one, and revokes ALL the caller's sessions (§13.4) — a successful
 * change signs the caller out everywhere, including the browser making
 * this call. A wrong current password 403s with `user.invalid_credentials`.
 */
export async function changeMyPassword(
  slug: string,
  payload: ChangePasswordPayload,
): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, '/me/change-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<void>(result)
}
