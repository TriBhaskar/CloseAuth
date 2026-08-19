// Stage UI-2b, Deliverable 5 (Vue side): the dedicated-fetch pattern applied
// to POST /verify-email/request and /verify-email/confirm — same reasoning
// as authRegistration.ts (no redirect, so no translation mechanism, and the
// backend consumes form-urlencoded, so a plain form-encoded fetch straight to
// the BFF's pure-relay routes is correct as-is).
//
// Deliberately NO client-side cooldown/attempt tracking here: the 429
// lockout state VerifyEmailView.vue shows is driven entirely by reading the
// real response status on each submit — never a locally-simulated timer that
// could drift out of sync with the backend's actual rate-limit window (see
// the stage prompt's explicit call-out on this point).
export interface VerifyEmailConfirmPayload {
  email: string
  code: string
  clientId?: string
}

// EmailVerificationController's confirm handler returns EMPTY bodies on
// every branch — the status code alone drives the outcome. FE-2d: a code
// that was ALREADY used is deliberately collapsed into the SAME 200 as a
// fresh verification (spec §6.2.4 — re-clicking an old email link reads as
// success, not an error), so there is no separate `alreadyUsed` kind here;
// `expired` (410) is the one new distinguishable outcome — see
// EmailVerificationService's class javadoc for why this narrow exception to
// the platform's usual "never distinguish why a token failed" rule is safe.
export type VerifyConfirmOutcome =
  | { kind: 'verified' }
  | { kind: 'expired' }
  | { kind: 'invalidCode' }
  | { kind: 'rateLimited' }
  | { kind: 'error'; status: number }

export async function confirmVerification(payload: VerifyEmailConfirmPayload): Promise<VerifyConfirmOutcome> {
  const form = new URLSearchParams()
  form.set('email', payload.email)
  form.set('code', payload.code)
  form.set('client_id', payload.clientId ?? '')

  let response: Response
  try {
    response = await fetch('/verify-email/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'omit',
      body: form.toString(),
    })
  } catch {
    return { kind: 'error', status: 0 }
  }

  if (response.ok) return { kind: 'verified' }
  if (response.status === 410) return { kind: 'expired' }
  if (response.status === 429) return { kind: 'rateLimited' }
  if (response.status === 400) return { kind: 'invalidCode' }
  return { kind: 'error', status: response.status }
}

export interface VerifyEmailRequestPayload {
  email: string
  clientId?: string
}

// POST /verify-email/request is ALWAYS 200 on the backend (uniform,
// enumeration-safe — a code is (re)sent only if the email maps to a user,
// and issuance is itself silently rate-limited) — so this resolves to a
// simple boolean: true if the request round-tripped at all, false only on a
// genuine network/transport failure. There is no per-outcome branching to
// do here, unlike confirmVerification.
export async function requestVerificationResend(payload: VerifyEmailRequestPayload): Promise<boolean> {
  const form = new URLSearchParams()
  form.set('email', payload.email)
  form.set('client_id', payload.clientId ?? '')

  try {
    const response = await fetch('/verify-email/request', {
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
