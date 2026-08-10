// Stage UI-3e: tenant registration-config — the API layer talking to
// internal/server/handlers_admin_tenant_settings.go's
// /t/{slug}/api/registration-config GET/PUT pair.
//
// Gotcha worth knowing before touching this file's PUT: an unrecognised
// `mode` string does NOT produce a clean 400. Jackson fails to bind it to
// the RegistrationMode enum (HttpMessageNotReadableException), and
// ApiExceptionHandler has no dedicated handler for that exception — its
// catch-all turns it into a 500 `internal_error`, which writeAdminAPIResult
// forwards as bad_gateway. This is a backend gap (flagged in the UI-3e stage
// report, not fixed here), and the reason updateRegistrationMode's payload
// type is the literal union below rather than a bare `string`: the SPA's
// <select> of exactly these four options is what actually prevents an admin
// from ever triggering the 500, not any client-side validation.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'

// Mirrors tenant/enums/RegistrationMode.java exactly — the FULL set, no more.
export type RegistrationMode = 'OPEN' | 'EMAIL_VERIFIED' | 'ADMIN_APPROVED' | 'INVITE_ONLY'

// Mirrors tenant/dto/RegistrationConfigView.java exactly.
export interface RegistrationConfigView {
  tenantId: string
  mode: RegistrationMode
}

export async function getRegistrationConfig(slug: string): Promise<AdminResult<RegistrationConfigView>> {
  const result = await tenantAdminFetch(slug, '/registration-config')
  return parseAdminResult<RegistrationConfigView>(result)
}

export async function updateRegistrationMode(
  slug: string,
  mode: RegistrationMode,
): Promise<AdminResult<RegistrationConfigView>> {
  const result = await tenantAdminFetch(slug, '/registration-config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  })
  return parseAdminResult<RegistrationConfigView>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/**
 * What each mode actually does to a NEW signup — sourced from the four
 * auth.strategy.*RegistrationStrategy classes, not invented. An admin
 * flipping this blind is a bad outcome (real, visible consequences: an
 * email round-trip, waiting on an admin, or requiring an invite), so the
 * settings view renders this alongside the <select>, not just the bare
 * enum value.
 */
export const REGISTRATION_MODE_DESCRIPTIONS: Record<RegistrationMode, { label: string; consequence: string }> = {
  OPEN: {
    label: 'Open',
    consequence: 'New users are ACTIVE immediately and can sign in right away — no email round-trip, no approval.',
  },
  EMAIL_VERIFIED: {
    label: 'Email verified',
    consequence:
      'New users start PENDING and are emailed a verification code. They become ACTIVE only after confirming it via /verify-email.',
  },
  ADMIN_APPROVED: {
    label: 'Admin approved',
    consequence:
      'New users start PENDING and stay that way until a tenant admin approves them from the Users page — no automatic path to ACTIVE.',
  },
  INVITE_ONLY: {
    label: 'Invite only',
    consequence:
      'Registration is refused (403) without a valid, matching invite token. A successful registration is ACTIVE immediately. Invites are issued via the backend API — this console has no invites screen yet.',
  },
}
