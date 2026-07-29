// Stage UI-2a, Deliverable 1 (Vue side): the client half of the redirect-vs-
// JSON-error translation mechanism. Talks to the BFF's new POST
// /api/auth/login (internal/server/handlers_login_json.go) — NOT the
// existing POST /login (that stays the pure-relay, raw-form-POST target a
// future stage's magic-link consumption still needs unchanged).
//
// Deliberately bypasses src/api/client.ts's shared `apiClient`/`request()`
// rather than reusing it: that helper's 401 handler
// (`if (response.status === 401 && !path.includes('/admin/login')) { ...
// globalThis.location.href = '/' }`) is a UI-0-survivor assumption from the
// old flat-admin-session model, and it would force a full-page navigation on
// ANY 401 — which directly conflicts with this stage's explicit requirement
// that a failed login shows an inline error with NO navigation at all. Given
// that helper's own file-level TODO already flags it for a future rework
// against the current principal model, adding a special-case path exclusion
// to it now (rather than a small dedicated fetch call here) would be
// patching code that's slated to change anyway. UI-2b/2c's own forms hit
// this same conflict (any tenant-end-user-facing form that must render an
// inline 401/400 without navigating) and should follow this same reasoning
// rather than routing through apiClient either.
export interface LoginPayload {
  email: string
  password: string
  rememberMe: boolean
  clientId?: string
}

export type LoginResult =
  | { ok: true; redirectTo: string }
  | { ok: false; error: string; errorDescription?: string }

export async function submitLogin(payload: LoginPayload): Promise<LoginResult> {
  let response: Response
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify(payload),
    })
  } catch {
    return { ok: false, error: 'network_error', errorDescription: 'Network error. Please check your connection.' }
  }

  const data = await response.json().catch(() => ({}) as Record<string, unknown>)

  if (response.ok) {
    const redirectTo = typeof data.redirectTo === 'string' ? data.redirectTo : ''
    if (!redirectTo) {
      return { ok: false, error: 'invalid_response', errorDescription: 'The server did not return a redirect URL.' }
    }
    return { ok: true, redirectTo }
  }

  return {
    ok: false,
    error: typeof data.error === 'string' ? data.error : 'invalid_credentials',
    errorDescription: typeof data.error_description === 'string' ? data.error_description : undefined,
  }
}
