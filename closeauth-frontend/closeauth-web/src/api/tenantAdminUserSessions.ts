// FE-4a: the user detail page's Sessions tab (spec §6.4.2) — talking to
// internal/server/handlers_admin_user_sessions.go's
// /t/{slug}/api/users/{userId}/sessions/** surface. Built on
// tenantAdminFetch/parseAdminResult, same as tenantAdminUsers.ts.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'

// Mirrors session/dto/MeSessionView.java exactly — deliberately OMITS the
// session key (that's the session's bearer secret, never leaked to any
// client); `id` is the handle used to revoke.
export interface AdminSessionView {
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

/** Every live session belonging to this user, in THIS tenant — never another tenant's, even for a valid session. */
export async function listUserSessions(slug: string, userId: string): Promise<AdminResult<AdminSessionView[]>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}/sessions`)
  return parseAdminResult<AdminSessionView[]>(result)
}

/** Revokes exactly one session — the 4-leg cascade (Redis hot entry, ledger row, refresh-token family, access-token marker). */
export async function revokeUserSession(
  slug: string,
  userId: string,
  sessionId: string,
): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(
    slug,
    `/users/${encodeURIComponent(userId)}/sessions/${encodeURIComponent(sessionId)}`,
    { method: 'DELETE' },
  )
  return parseAdminResult<void>(result)
}

/**
 * Revokes every session belonging to this user — a single backend call, not
 * a loop over listUserSessions' rows. Pair with TypedConfirmDialog in the
 * UI (spec §6.4.2's "Revoke all sessions (typed confirm)").
 */
export async function revokeAllUserSessions(slug: string, userId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}/sessions`, {
    method: 'DELETE',
  })
  return parseAdminResult<void>(result)
}
