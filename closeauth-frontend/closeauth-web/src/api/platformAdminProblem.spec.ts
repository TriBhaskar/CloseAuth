import { describe, it, expect } from 'vitest'
import { parsePlatformResult } from '@/api/platformAdminProblem'
import type { PlatformAdminFetchResult } from '@/api/platformAdminClient'

// FE-1.11: parsePlatformResult previously dropped `loginPath` on a
// sessionExpired outcome, silently relying on every caller checking
// `result.kind === 'sessionExpired'` on the raw PlatformAdminFetchResult
// BEFORE calling this — platformAdminTenants.ts, platformAdmins.ts and
// platformAdminTenantUsers.ts never did. Fixed to carry it through on the
// parsed AdminResult itself.
describe('parsePlatformResult', () => {
  it('a sessionExpired outcome becomes an unauthorized error carrying loginPath, not silently dropped', async () => {
    const result = await parsePlatformResult(
      { kind: 'sessionExpired', loginPath: '/platform/login?returnTo=%2Fplatform%2Fconsole%2Ftenants' } satisfies PlatformAdminFetchResult,
    )

    expect(result.kind).toBe('error')
    if (result.kind !== 'error') throw new Error('expected error kind')
    expect(result.category).toBe('unauthorized')
    expect(result.code).toBe('session_expired')
    expect(result.loginPath).toBe('/platform/login?returnTo=%2Fplatform%2Fconsole%2Ftenants')
  })

  it('a genuine response delegates to parseAdminResult unchanged', async () => {
    const response = {
      ok: false,
      status: 409,
      json: () => Promise.resolve({ error: 'tenant.duplicate_slug', error_description: 'Already taken.' }),
    } as unknown as Response

    const result = await parsePlatformResult({ kind: 'response', response })

    expect(result.kind).toBe('conflict')
    if (result.kind !== 'conflict') throw new Error('expected conflict kind')
    expect(result.code).toBe('tenant.duplicate_slug')
  })
})
