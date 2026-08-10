// Stage UI-3b: the shared response parser for the tenant-admin console's
// CRUD surface (tenantAdminUsers.ts, tenantAdminRoles.ts, and whatever later
// stages — clients, resource servers, branding, audit — add on top of it).
// Turns internal/server/admin_api_result.go's house envelope into a
// discriminated union the SPA can switch on, following the standing house
// rule (tenantAdminPing.ts, authRegistration.ts): never throw, always return
// a typed result.
//
// Stage UI-3e adds `code` to the `error` kind: audit's `audit.invalid_*`
// 400s (AuditQueryParams.java) are VALIDATION-category but carry an EMPTY
// exception context, so ProblemDetails emits no `errors` map — the code is
// the only field signal available, and until now it was discarded here. See
// tenantAdminAudit.ts's AUDIT_ERROR_FIELDS for the consumer. Purely
// additive — every pre-existing caller that only reads `.message` is
// unaffected.
import type { TenantAdminFetchResult } from '@/api/tenantAdminClient'

export type AdminResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'reauth' }
  | { kind: 'validationErrors'; code: string; errors: Record<string, string> }
  | { kind: 'conflict'; code: string; message: string }
  | { kind: 'error'; status: number; code: string; message: string }

interface AdminErrorBody {
  error?: string
  error_description?: string
  errors?: Record<string, unknown>
}

/**
 * Parses a tenantAdminFetch() result against the BFF's admin-API envelope
 * (internal/server/admin_api_result.go):
 *   - 2xx decodes as T (undefined for a body-less 204, e.g. assign/revoke).
 *   - a 400 carrying a non-empty `errors` map becomes `validationErrors`,
 *     field-keyed exactly like authRegistration.ts's identical 400-handling
 *     — the backend's CommandValidator/`@Valid` keys errors by the command's
 *     own field names, which match this stage's form field ids. `code` rides
 *     along too (UI-3d): not every 400 with an `errors` map is field-keyed —
 *     `application_role.scope_rs_mismatch` is VALIDATION-category with an
 *     `errors` map keyed by RS ids, not form fields — so a caller that needs
 *     to tell the two apart can branch on `code` before trusting `errors` as
 *     field names. Existing callers that only read `.errors` are unaffected.
 *   - a 409 preserves its domain `code` (e.g. "tenant_role.last_admin") as
 *     `conflict.code`, so a caller can show the SPECIFIC reason rather than
 *     a generic conflict banner — this is the whole point of forwarding
 *     problem details through the BFF instead of collapsing them.
 *   - anything else is a generic `error`, carrying the backend's own detail
 *     message when the BFF was able to parse one.
 */
export async function parseAdminResult<T>(result: TenantAdminFetchResult): Promise<AdminResult<T>> {
  if (result.kind === 'reauth') return { kind: 'reauth' }

  const { response } = result

  if (response.ok) {
    if (response.status === 204) {
      return { kind: 'ok', value: undefined as T }
    }
    const value = (await response.json().catch(() => undefined)) as T
    return { kind: 'ok', value }
  }

  const body = (await response.json().catch(() => null)) as AdminErrorBody | null

  if (response.status === 400 && body?.errors && Object.keys(body.errors).length > 0) {
    const mapped: Record<string, string> = {}
    for (const [field, message] of Object.entries(body.errors)) {
      mapped[field] = typeof message === 'string' ? message : String(message)
    }
    return { kind: 'validationErrors', code: body?.error ?? 'validation.failed', errors: mapped }
  }

  if (response.status === 409) {
    return {
      kind: 'conflict',
      code: body?.error ?? 'conflict',
      message: body?.error_description ?? 'This action conflicts with the current state.',
    }
  }

  return {
    kind: 'error',
    status: response.status,
    code: body?.error ?? '',
    message: body?.error_description ?? body?.error ?? `Request failed with status ${response.status}`,
  }
}

/**
 * A human-readable summary for every non-'ok'/'reauth' result kind — a
 * generic-banner message. Callers rendering the validationErrors kind's
 * per-field display should use `result.errors` directly (via FormField)
 * rather than this; it exists for the cases a banner IS the right
 * treatment (a stray validation failure with no matching form field, a
 * conflict, a generic error).
 */
export function describeAdminError(result: Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>): string {
  switch (result.kind) {
    case 'validationErrors':
      return Object.values(result.errors)[0] ?? 'The request was invalid.'
    case 'conflict':
    case 'error':
      return result.message
  }
}
