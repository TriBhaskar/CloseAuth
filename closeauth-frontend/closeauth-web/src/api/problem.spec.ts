import { describe, it, expect } from 'vitest'
import { parseAdminResult, describeAdminError, errorStateProps, type AdminResult } from '@/api/problem'
import type { TenantAdminFetchResult } from '@/api/tenantAdminClient'
import { unreachableResponse } from '@/api/transport'

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

// FE-1.11 (spec §7.3): the RFC 7807 -> UI category mapping, implemented
// once here rather than re-derived per caller. 400 (validationErrors) and
// 409 (conflict) are their own arms already, proven above — this covers
// what's left of the table: 401/403/404/429/5xx, plus network.
describe('parseAdminResult — §7.3 category mapping', () => {
  it.each([
    [401, 'unauthorized'],
    [403, 'forbidden'],
    [404, 'notFound'],
    [429, 'rateLimited'],
    [500, 'server'],
    [502, 'server'],
    [418, 'unknown'],
  ] as const)('a %i response is categorized %s', async (status, category) => {
    const result = await parseAdminResult(fakeResponse(status, { error: 'x', error_description: 'x' }))
    expect(result.kind).toBe('error')
    if (result.kind !== 'error') throw new Error('expected error kind')
    expect(result.category).toBe(category)
  })

  it('a synthetic unreachable response (transport.ts) is categorized network, distinct from a real 503', async () => {
    const unreachable = await parseAdminResult({ kind: 'response', response: unreachableResponse() })
    expect(unreachable.kind).toBe('error')
    if (unreachable.kind !== 'error') throw new Error('expected error kind')
    expect(unreachable.category).toBe('network')

    const real503 = await parseAdminResult(fakeResponse(503, { error: 'backend_error', error_description: 'down' }))
    expect(real503.kind).toBe('error')
    if (real503.kind !== 'error') throw new Error('expected error kind')
    expect(real503.category).toBe('server')
  })
})

describe('describeAdminError — §7.3 fixed copy', () => {
  it('403 gets the fixed page-level copy regardless of the backend message', async () => {
    const result = await parseAdminResult(fakeResponse(403, { error: 'access_denied', error_description: 'nope' }))
    expect(describeAdminError(result as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>)).toBe(
      "You don't have access to this.",
    )
  })

  it('429 gets the fixed wait-and-retry copy', async () => {
    const result = await parseAdminResult(fakeResponse(429, { error: 'rate_limited', error_description: 'slow down' }))
    expect(describeAdminError(result as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>)).toBe(
      'Too many requests. Try again in a moment.',
    )
  })

  it('404/5xx keep the backend detail message as-is', async () => {
    const notFound = await parseAdminResult(fakeResponse(404, { error: 'user.not_found', error_description: 'No such user.' }))
    expect(describeAdminError(notFound as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>)).toBe(
      'No such user.',
    )
  })
})

describe('errorStateProps', () => {
  it('server and network categories are retryable; forbidden/notFound/rateLimited/unauthorized are not', async () => {
    const server = await parseAdminResult(fakeResponse(500, { error: 'internal_error', error_description: 'boom' }))
    const network = await parseAdminResult({ kind: 'response', response: unreachableResponse() })
    const forbidden = await parseAdminResult(fakeResponse(403, { error: 'access_denied', error_description: 'nope' }))

    expect(errorStateProps(server as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>).retryable).toBe(true)
    expect(errorStateProps(network as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>).retryable).toBe(true)
    expect(errorStateProps(forbidden as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>).retryable).toBe(
      false,
    )
  })

  it('traceId is always undefined — trace_id does not exist end-to-end yet (tracked backend dependency)', async () => {
    const server = await parseAdminResult(fakeResponse(500, { error: 'internal_error', error_description: 'boom' }))
    expect(errorStateProps(server as Exclude<AdminResult<unknown>, { kind: 'ok' } | { kind: 'reauth' }>).traceId).toBeUndefined()
  })
})
