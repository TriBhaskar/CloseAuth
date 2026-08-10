// Stage UI-4: the platform-admin console's fetch layer, talking to the BFF's
// /platform/api/** surface (internal/server/handlers_platform_*.go) — the
// cross-tenant sibling of tenantAdminClient.ts, NOT src/api/client.ts's
// apiClient/request(): that helper's global 401 handling assumes a single
// flat session and is wrong here too, for the same reason
// tenantAdminClient.ts bypasses it.
//
// What IS reused from client.ts: fetchCsrfToken()/getCsrfToken() — the CSRF
// cookie/token is Path=/ and surface-agnostic (one BFF-wide token), so
// there's no reason to duplicate that cache, exactly like tenantAdminClient.ts.
//
// The one genuine behavioral difference from tenantAdminClient.ts: there is
// no silent re-auth here. The platform-admin token is access-only with a
// 5-minute TTL and no refresh (PlatformAdminTokenService) — when the BFF
// reports the session has expired, the only remedy is signing in again, so
// this navigates to the login form via a plain client-side route push
// (handled by the caller, not window.location.assign — there is no
// backend SSO entry point to reach, unlike the tenant console's reauth
// path), and the result kind is named 'sessionExpired', not 'reauth', to
// keep that distinction honest at every call site.
import { fetchCsrfToken, getCsrfToken } from '@/api/client'

export type PlatformAdminFetchResult =
  | { kind: 'response'; response: Response }
  | { kind: 'sessionExpired'; loginPath: string }

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/**
 * Calls `/platform/api{path}`, attaching the CSRF header on mutating
 * requests. If the BFF responds `401 {"error":"session_expired","loginPath":...}`
 * (RequirePlatformSession's fail-closed-on-expiry outcome — see
 * internal/middleware/platform_guard.go), this returns
 * `{kind:'sessionExpired', loginPath}` so the caller can navigate there
 * itself and stop rendering, rather than trying to interpret a 401 body as
 * data.
 */
export async function platformAdminFetch(
  path: string,
  init: RequestInit = {},
): Promise<PlatformAdminFetchResult> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)

  if (MUTATING_METHODS.has(method)) {
    const token = getCsrfToken() ?? (await fetchCsrfToken())
    if (token) headers.set('X-CSRF-Token', token)
  }

  let response: Response
  try {
    response = await fetch(`/platform/api${path}`, { ...init, headers, credentials: 'include' })
  } catch {
    // Backend/BFF unreachable — synthesize a response the caller can treat
    // uniformly rather than throwing (matches the house discriminated-union
    // convention: callers branch on shape, never on a caught exception).
    return { kind: 'response', response: new Response(null, { status: 503 }) }
  }

  if (response.status === 401) {
    const body = (await response
      .clone()
      .json()
      .catch(() => null)) as { error?: string; loginPath?: string } | null
    if (body?.error === 'session_expired' && body.loginPath) {
      return { kind: 'sessionExpired', loginPath: body.loginPath }
    }
  }

  return { kind: 'response', response }
}
