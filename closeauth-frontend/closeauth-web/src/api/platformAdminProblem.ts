// Stage UI-4: the platform-admin console's response parser. Rather than
// duplicating problem.ts's 400-errors/409-code RFC 7807 mapping
// (internal/server/platform_api_result.go forwards the exact same problem
// shape as internal/server/admin_api_result.go, deliberately reusing its
// writeAdminAPIBody/writeProblemError helpers verbatim — see that file's doc
// comment), this module ADAPTS PlatformAdminFetchResult into the shape
// parseAdminResult already expects and delegates to it wholesale. Zero type
// churn on problem.ts's existing callers; a 'reauth' AdminResult
// arm is simply never produced on this surface (there is nothing here that
// silently renews — 'sessionExpired' maps to a plain 'error' arm instead,
// carrying the loginPath the caller needs to navigate).
import type { PlatformAdminFetchResult } from '@/api/platformAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'

/**
 * Parses a platformAdminFetch() result. A session-expired outcome becomes a
 * plain `error` result (category `'unauthorized'`) rather than a distinct
 * arm — FE-1.11 fixed this to carry the BFF's `loginPath` along on the
 * result itself (previously dropped here, silently relying on every caller
 * checking `result.kind === 'sessionExpired'` on the raw
 * PlatformAdminFetchResult first — `platformAdminTenants.ts`,
 * `platformAdmins.ts` and `platformAdminTenantUsers.ts` never did), so a
 * caller reading only the parsed `AdminResult` can still navigate there.
 */
export async function parsePlatformResult<T>(result: PlatformAdminFetchResult): Promise<AdminResult<T>> {
  if (result.kind === 'sessionExpired') {
    return {
      kind: 'error',
      status: 401,
      code: 'session_expired',
      message: 'Your platform-admin session has expired. Sign in again.',
      category: 'unauthorized',
      loginPath: result.loginPath,
    }
  }
  // parseAdminResult only inspects the 'response' arm of its own union type,
  // so a same-shaped { kind: 'response'; response } object satisfies it —
  // constructed explicitly here (rather than a type-cast) to keep this
  // adapter honest if either union ever grows a new member.
  return parseAdminResult<T>({ kind: 'response', response: result.response })
}
