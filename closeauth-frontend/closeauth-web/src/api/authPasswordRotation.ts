// Phase 4a: the client half of the tenant-onboarding password-rotation
// confirm step. Modeled on authLogin.ts's JSON/redirectTo pattern, not
// authPasswordReset.ts's — unlike self-service reset, rotation's confirm
// call establishes the real session (a Set-Cookie the BFF relays) and
// resolves to a redirect the caller must navigate to, exactly like login.
// Same reason authLogin.ts bypasses src/api/client.ts's shared apiClient:
// see that file's header comment for the 401-auto-logout landmine this
// avoids (not directly relevant here since rotation's confirm never 401s,
// but the dedicated-fetch convention is kept consistent across every
// Surface-1 hosted-page call site).
export interface PasswordRotationConfirmPayload {
  token: string
  password: string
  clientId: string
  // The SAME opaque, already-once-decoded string PasswordRotationView.vue
  // read via `new URLSearchParams(window.location.search).get('authorize_query')`.
  // Forwarded verbatim — never re-encoded — see that view's header comment
  // for the full round-trip explanation. Omitted (undefined) on the
  // emailed-link on-ramp, which carries none.
  authorizeQuery?: string
}

// Three distinct failure shapes, not one generic error — the view renders
// different copy for each (see PasswordRotationView.vue):
//   - 'invalid': the backend's uniform 400 (bad/expired/consumed token,
//     unresolvable client_id, malformed authorize_query) — nothing changed
//     server-side, the honest "get a new link" message applies.
//   - 'missingRedirect': a 200 with no usable redirectTo. Unlike the other
//     two, the password WAS already changed server-side (this endpoint's
//     200 IS the success signal) — the copy must not imply the attempt
//     failed.
//   - 'error': network failure, 5xx, or the BFF's own 502 bad_gateway.
//     Unknown whether anything changed server-side; safe, generic retry copy.
export type PasswordRotationConfirmResult =
  | { ok: true; redirectTo: string }
  | { ok: false; kind: 'invalid' }
  | { ok: false; kind: 'missingRedirect' }
  | { ok: false; kind: 'error' }

export async function confirmPasswordRotation(
  payload: PasswordRotationConfirmPayload,
): Promise<PasswordRotationConfirmResult> {
  let response: Response
  try {
    response = await fetch('/api/auth/password-rotation/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify(payload),
    })
  } catch {
    return { ok: false, kind: 'error' }
  }

  if (response.ok) {
    const data = await response.json().catch(() => ({}) as Record<string, unknown>)
    const redirectTo = typeof data.redirectTo === 'string' ? data.redirectTo : ''
    if (!redirectTo) {
      return { ok: false, kind: 'missingRedirect' }
    }
    return { ok: true, redirectTo }
  }

  if (response.status === 400) {
    return { ok: false, kind: 'invalid' }
  }
  return { ok: false, kind: 'error' }
}
