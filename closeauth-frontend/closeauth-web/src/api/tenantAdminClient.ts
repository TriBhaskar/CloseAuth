// Stage UI-3a: the tenant-admin console's fetch layer, talking to the BFF's
// /t/{slug}/api/** surface (internal/server/handlers_admin_*.go) — NOT
// src/api/client.ts's apiClient/request(): that helper's global 401 handler
// force-navigates to '/' on ANY 401, which is wrong here twice over — a
// tenant-admin 401 usually means "silently re-authorize" (reauth_required),
// not "log out", and the navigation target must be this tenant's own
// /t/{slug}/admin/reauth, not a hardcoded '/'. See authLogin.ts's identical
// reasoning for bypassing the same helper on Surface 1.
//
// What IS reused from client.ts: fetchCsrfToken()/getCsrfToken() — the CSRF
// cookie/token is Path=/ and tenant-agnostic (one BFF-wide token), so there's
// no reason to duplicate that cache.
import { fetchCsrfToken, getCsrfToken } from '@/api/client'

export type TenantAdminFetchResult =
  | { kind: 'response'; response: Response }
  | { kind: 'reauth' }

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/**
 * Calls `/t/{slug}/api{path}`, attaching the CSRF header on mutating
 * requests. If the BFF responds `401 {"error":"reauth_required","reauthPath":...}`
 * (RequireAdminSession's lazy silent-reauth trigger — see
 * internal/middleware/auth_guard.go), this performs the full-page navigation
 * to reauthPath itself (a real top-level browser navigation is required —
 * see AuthorizeURL's own doc comment on why fetch() can never substitute for
 * this) and returns `{kind:'reauth'}` so the caller can stop rendering
 * rather than trying to interpret a 401 body as data.
 */
export async function tenantAdminFetch(
  slug: string,
  path: string,
  init: RequestInit = {},
): Promise<TenantAdminFetchResult> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)

  if (MUTATING_METHODS.has(method)) {
    const token = getCsrfToken() ?? (await fetchCsrfToken())
    if (token) headers.set('X-CSRF-Token', token)
  }

  let response: Response
  try {
    response = await fetch(`/t/${slug}/api${path}`, { ...init, headers, credentials: 'include' })
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
      .catch(() => null)) as { error?: string; reauthPath?: string } | null
    if (body?.error === 'reauth_required' && body.reauthPath) {
      const returnTo = window.location.pathname + window.location.search
      window.location.assign(`${body.reauthPath}?returnTo=${encodeURIComponent(returnTo)}`)
      return { kind: 'reauth' }
    }
  }

  return { kind: 'response', response }
}
