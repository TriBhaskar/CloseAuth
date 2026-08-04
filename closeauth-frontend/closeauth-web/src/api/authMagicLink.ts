// Stage UI-2c-i, Deliverable 3 (Vue side): the dedicated-fetch pattern
// (authLogin.ts/authRegistration.ts's established reasoning) applied to
// POST /magic-link/request — magic-link's REQUEST step only. The consume
// step (GET /magic-link/consume) needs no Vue-side counterpart at all
// (Design Decision #1 — the emailed link points straight at the backend's
// own origin, never through this SPA).
//
// MagicLinkController.requestMagicLink is ALWAYS a bare 200 (enumeration-
// safe: identical whether or not the email exists or the client resolves),
// so — same shape as authVerifyEmail.ts's requestVerificationResend — this
// resolves to a simple boolean: true if the request round-tripped as a 200,
// false only on a genuine network/transport failure or an unexpected
// non-200 (there is no per-outcome branching the backend's contract gives
// us to do here).
export interface MagicLinkRequestPayload {
  email: string
  clientId?: string
}

export async function requestMagicLink(payload: MagicLinkRequestPayload): Promise<boolean> {
  const form = new URLSearchParams()
  form.set('email', payload.email)
  form.set('client_id', payload.clientId ?? '')

  try {
    const response = await fetch('/magic-link/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'omit',
      body: form.toString(),
    })
    return response.ok
  } catch {
    return false
  }
}
