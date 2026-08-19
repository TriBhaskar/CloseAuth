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
import { fetchCsrfToken, getCsrfToken } from '@/api/csrf'
import { unreachableResponse } from '@/api/transport'

export type TenantAdminFetchResult =
  | { kind: 'response'; response: Response }
  | { kind: 'reauth' }

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/**
 * §7.2: "If a response's tenant_id doesn't match the route's tenant, the
 * app treats it as a fatal integrity error: clear the session store,
 * redirect to /t/{tenantId}, and log it." Checked here — the one transport
 * every tenant-admin console call goes through — rather than at each of the
 * ~30 individual call sites across tenantAdminUsers.ts and friends, so it's
 * a single implementation point with zero call-site churn.
 *
 * Deliberately NOT awaited by its caller (tenantAdminFetch): this is a
 * safety-net integrity check, not part of the primary response path, and
 * every one of those ~30 call sites' own tests already assert precise
 * timing/tick counts against tenantAdminFetch resolving as soon as the real
 * response is available. Whatever this finds, the caller's response is
 * already correct or already about to be superseded by the redirect below.
 * Wrapped in try/catch end-to-end so a body-peek failure (a non-JSON body,
 * a test double without .clone()/.json()) can never surface as an unhandled
 * rejection.
 *
 * The session store and router are loaded via dynamic import rather than a
 * static one at the top of this file: stores/tenantAdmin.ts already imports
 * api/tenantAdminSession.ts, which imports THIS module for tenantAdminFetch
 * — a static import of the store here would create a real module-graph
 * cycle (and app/router.ts pulls the store in too, via app/guards.ts). By
 * the time this runs — request time, well after the app has booted — both
 * modules are already loaded, so the dynamic import resolves from cache
 * immediately; it exists only to keep the STATIC import graph acyclic, not
 * to defer any real work.
 */
async function checkTenantMismatch(slug: string, path: string, response: Response): Promise<void> {
  if (!response.ok) return

  try {
    const peek = typeof response.clone === 'function' ? response.clone() : response
    const body = (await peek.json()) as { tenantId?: string } | null
    if (!body?.tenantId) return

    const { useTenantAdminSessionStore } = await import('@/stores/tenantAdmin')
    const store = useTenantAdminSessionStore()
    const expected = store.state.kind === 'active' ? store.state.tenantId : null
    if (!expected || body.tenantId === expected) return

    console.error(
      `[closeauth] tenant_id mismatch on /t/${slug}/api${path}: response carried "${body.tenantId}", session is scoped to "${expected}" — clearing session and redirecting`,
    )
    store.state = { kind: 'anonymous' }
    const { default: router } = await import('@/app/router')
    await router.replace(`/t/${slug}`)
  } catch {
    // A malformed/non-JSON body or a test double missing .clone()/.json()
    // — nothing to check, and this must never break the primary response.
  }
}

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
    // Marked (transport.ts) so problem.ts can tell this apart from a real 503.
    return { kind: 'response', response: unreachableResponse() }
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

  void checkTenantMismatch(slug, path, response)

  return { kind: 'response', response }
}
