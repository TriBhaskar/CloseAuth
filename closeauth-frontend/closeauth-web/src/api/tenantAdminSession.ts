// Stage UI-3a: GET/POST wrappers for the BFF's session-lifecycle endpoints
// (internal/server/handlers_admin_session.go). Discriminated-union outcomes,
// never throwing — the house convention every src/api/* module here follows
// (see authLogin.ts, authRegistration.ts).
import { tenantAdminFetch } from '@/api/tenantAdminClient'

export type TenantAdminSessionState =
  | {
      kind: 'active'
      tenantId: string
      userId: string
      email: string
      tenantRoles: string[]
      accessTokenExpiresAt: string
    }
  | { kind: 'reauth' }
  | { kind: 'anonymous' }
  | { kind: 'denied'; reason: string }
  | { kind: 'unreachable' }

interface SessionResponseBody {
  slug: string
  authenticated: boolean
  reauthRequired?: boolean
  denied?: boolean
  deniedReason?: string
  tenantId?: string
  userId?: string
  email?: string
  tenantRoles?: string[]
  accessTokenExpiresAt?: string
}

/**
 * GET /t/{slug}/api/session — a state PROBE, not a protected resource (see
 * the Go handler's own doc comment: it always returns 200). This function
 * translates that single always-200 shape into the five outcomes the
 * router guard and landing page actually care about.
 */
export async function fetchSession(slug: string): Promise<TenantAdminSessionState> {
  const result = await tenantAdminFetch(slug, '/session')
  if (result.kind === 'reauth') return { kind: 'reauth' }

  const { response } = result
  if (!response.ok) return { kind: 'unreachable' }

  const data = (await response.json().catch(() => null)) as SessionResponseBody | null
  if (!data) return { kind: 'unreachable' }

  if (data.denied) return { kind: 'denied', reason: data.deniedReason ?? 'unknown' }
  if (!data.authenticated) return { kind: 'anonymous' }
  if (data.reauthRequired) return { kind: 'reauth' }

  return {
    kind: 'active',
    tenantId: data.tenantId ?? '',
    userId: data.userId ?? '',
    email: data.email ?? '',
    tenantRoles: data.tenantRoles ?? [],
    accessTokenExpiresAt: data.accessTokenExpiresAt ?? '',
  }
}

/**
 * POST /t/{slug}/api/signout — clears the BFF's own console session only.
 * Deliberately does NOT call the backend's /logout cascade (settled
 * decision — see handleAdminSignOut's doc comment on the Go side): signing
 * back in afterward is silent.
 */
export async function signOut(slug: string): Promise<boolean> {
  const result = await tenantAdminFetch(slug, '/signout', { method: 'POST' })
  if (result.kind === 'reauth') return true
  return result.response.ok
}

/**
 * POST /t/{slug}/api/denied/dismiss — the "try a different account" escape
 * hatch for the non-admin refusal loop (see TenantAdminDeniedView.vue).
 */
export async function dismissDenial(slug: string): Promise<boolean> {
  const result = await tenantAdminFetch(slug, '/denied/dismiss', { method: 'POST' })
  if (result.kind === 'reauth') return false
  return result.response.ok
}
