// Stage UI-2b, Deliverable 4 (Vue side): the dedicated-fetch pattern UI-2a
// established (see authLogin.ts's header comment) applied to POST /register.
// Genuinely simpler than login's case: the backend's /register never
// redirects (API_REFERENCE.md §1 — it's always a plain 200 JSON body or a
// 400/403/409 problem+json/empty body), so there is no redirect-vs-JSON-error
// translation to do at all, on either side — not just "no Go-side
// translation handler" (handlers_login_json.go's kind) but no request-side
// transcoding either: RegistrationController consumes
// application/x-www-form-urlencoded (confirmed against its
// @PostMapping(consumes=...) — the docs alone don't show this), so this
// module posts a form-encoded body straight to the BFF's pure-relay /register
// route (handlers_registration_proxy.go), same as a native form POST would,
// just via fetch() so a failure can render inline with no navigation.
export interface RegisterPayload {
  email: string
  password: string
  firstName?: string
  lastName?: string
  phone?: string
  clientId?: string
  /** Carried silently when the page was reached via a `?invite=` link — never a user-editable form field. */
  inviteToken?: string
}

// The six outcomes RegisterView.vue branches on (Deliverable 4's spec,
// mapped 1:1 to RegistrationResult.status/mode or the documented error
// statuses):
//   - ACTIVE                              -> 'active'
//   - PENDING, emailVerificationSent=true -> 'pendingVerification'
//   - PENDING, emailVerificationSent=false-> 'pendingApproval'
//   - 409 (email already registered)      -> 'emailConflict'
//   - 403 (invite-only, missing/invalid)   -> 'inviteRequired'
//   - 400 with a validation `errors` map   -> 'validationErrors'
// Anything else (a bad client_id's empty-bodied 400, a network error, a 5xx)
// falls through to 'error' — a generic banner, not a field-level treatment.
export type RegisterOutcome =
  | { kind: 'active' }
  | { kind: 'pendingVerification' }
  | { kind: 'pendingApproval' }
  | { kind: 'emailConflict' }
  | { kind: 'inviteRequired' }
  | { kind: 'validationErrors'; errors: Record<string, string> }
  | { kind: 'error'; status: number }

export async function submitRegistration(payload: RegisterPayload): Promise<RegisterOutcome> {
  const form = new URLSearchParams()
  form.set('email', payload.email)
  form.set('password', payload.password)
  if (payload.firstName) form.set('first_name', payload.firstName)
  if (payload.lastName) form.set('last_name', payload.lastName)
  if (payload.phone) form.set('phone', payload.phone)
  // client_id is a MANDATORY @RequestParam on the backend (unlike /login's
  // optional one) — always sent, even empty, so a missing-param binding
  // failure never happens; an empty/unknown client_id instead resolves to
  // the backend's own clean 400 (tenantResolver.resolveTenantId empty ->
  // ResponseEntity.badRequest().build()).
  form.set('client_id', payload.clientId ?? '')
  if (payload.inviteToken) form.set('invite_token', payload.inviteToken)

  let response: Response
  try {
    response = await fetch('/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'omit',
      body: form.toString(),
    })
  } catch {
    return { kind: 'error', status: 0 }
  }

  if (response.ok) {
    const data = (await response.json().catch(() => ({}))) as {
      status?: string
      emailVerificationSent?: boolean
    }
    if (data.status === 'ACTIVE') return { kind: 'active' }
    if (data.status === 'PENDING') {
      return data.emailVerificationSent === true
        ? { kind: 'pendingVerification' }
        : { kind: 'pendingApproval' }
    }
    return { kind: 'error', status: response.status }
  }

  if (response.status === 409) return { kind: 'emailConflict' }
  if (response.status === 403) return { kind: 'inviteRequired' }
  if (response.status === 400) {
    // CommandValidator's ConstraintViolation.getPropertyPath() keys the
    // `errors` map by RegisterUserCommand's own field names — email,
    // password, firstName, lastName, phone (confirmed against backend
    // source) — which are exactly this form's field ids, by design, not
    // coincidence: no separate wire-name/field-id mapping table is needed.
    const problem = (await response.json().catch(() => ({}))) as { errors?: Record<string, unknown> }
    const errors = problem.errors
    if (errors && Object.keys(errors).length > 0) {
      const mapped: Record<string, string> = {}
      for (const [field, message] of Object.entries(errors)) {
        mapped[field] = typeof message === 'string' ? message : String(message)
      }
      return { kind: 'validationErrors', errors: mapped }
    }
    return { kind: 'error', status: 400 }
  }
  return { kind: 'error', status: response.status }
}
