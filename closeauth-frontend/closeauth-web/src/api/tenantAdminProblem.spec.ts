import { describe, it, expect } from 'vitest'
import { parseAdminResult } from '@/api/tenantAdminProblem'
import type { TenantAdminFetchResult } from '@/api/tenantAdminClient'

// Stage UI-3e: the `error` kind gained a `code` field so a caller (audit's
// AUDIT_ERROR_FIELDS, branding's BRANDING_ERROR_FIELDS) can map a
// domain-coded 400/500 that carries no `errors` map onto a specific form
// field, rather than only ever falling back to a generic banner.
function fakeResponse(status: number, body: unknown): TenantAdminFetchResult {
  return {
    kind: 'response',
    response: {
      ok: status >= 200 && status < 300,
      status,
      json: () => Promise.resolve(body),
    } as unknown as Response,
  }
}

describe('parseAdminResult', () => {
  it('an error-kind result carries the backend code, not just the message', async () => {
    const result = await parseAdminResult(
      fakeResponse(400, {
        error: 'audit.invalid_event_type',
        error_description: "Invalid value for 'event_type': NOT_A_TYPE",
      }),
    )

    expect(result.kind).toBe('error')
    if (result.kind !== 'error') throw new Error('expected error kind')
    expect(result.status).toBe(400)
    expect(result.code).toBe('audit.invalid_event_type')
    expect(result.message).toBe("Invalid value for 'event_type': NOT_A_TYPE")
  })

  it('an error-kind result with no parseable body has an empty code, not a crash', async () => {
    const result = await parseAdminResult(
      fakeResponse(502, null),
    )

    expect(result.kind).toBe('error')
    if (result.kind !== 'error') throw new Error('expected error kind')
    expect(result.code).toBe('')
  })

  it('a 400 WITH an errors map still takes the validationErrors branch, unaffected by the code addition', async () => {
    const result = await parseAdminResult(
      fakeResponse(400, {
        error: 'validation.failed',
        error_description: 'Request validation failed',
        errors: { primaryColor: 'must be a #RRGGBB hex color' },
      }),
    )

    expect(result.kind).toBe('validationErrors')
    if (result.kind !== 'validationErrors') throw new Error('expected validationErrors kind')
    expect(result.errors.primaryColor).toBe('must be a #RRGGBB hex color')
  })
})
