// Stage UI-2c-i, Deliverable 4 (Vue side): the dedicated-fetch pattern
// applied to POST /password-reset/request and /password-reset/confirm.
// Neither ever redirects (PasswordResetController source, confirmed in the
// stage report), so — same as authRegistration.ts/authVerifyEmail.ts — no
// redirect-vs-JSON-error translation is needed on either side.
export interface PasswordResetRequestPayload {
  email: string
  clientId?: string
}

// POST /password-reset/request is ALWAYS 200 (enumeration-safe — a link is
// emailed only if the account exists, never revealed either way), so this
// resolves to a simple boolean, same shape as requestMagicLink /
// requestVerificationResend.
export async function requestPasswordReset(payload: PasswordResetRequestPayload): Promise<boolean> {
  const form = new URLSearchParams()
  form.set('email', payload.email)
  form.set('client_id', payload.clientId ?? '')

  try {
    const response = await fetch('/password-reset/request', {
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

export interface PasswordResetConfirmPayload {
  token: string
  password: string
  clientId?: string
}

// PasswordResetController.confirm's only two documented outcomes: 200 (the
// reset — and the post-reset session/token revocation cascade — took
// effect) or a GENERIC 400 (invalid/expired/already-used token — the
// backend deliberately never says which, so neither does this mapping).
export type PasswordResetConfirmOutcome =
  | { kind: 'reset' }
  | { kind: 'invalid' }
  | { kind: 'error'; status: number }

export async function confirmPasswordReset(payload: PasswordResetConfirmPayload): Promise<PasswordResetConfirmOutcome> {
  const form = new URLSearchParams()
  form.set('token', payload.token)
  form.set('password', payload.password)
  form.set('client_id', payload.clientId ?? '')

  let response: Response
  try {
    response = await fetch('/password-reset/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'omit',
      body: form.toString(),
    })
  } catch {
    return { kind: 'error', status: 0 }
  }

  if (response.ok) return { kind: 'reset' }
  if (response.status === 400) return { kind: 'invalid' }
  return { kind: 'error', status: response.status }
}
