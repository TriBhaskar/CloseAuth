// Stage UI-4: GET/POST wrappers for the BFF's platform-admin session
// endpoints (internal/server/handlers_platform_auth.go). Discriminated-union
// outcomes, never throwing — the house convention every src/api/* module
// here follows (see tenantAdminSession.ts, authLogin.ts).
import { platformAdminFetch } from '@/api/platformAdminClient'

export type PlatformAdminSessionState =
  | { kind: 'active'; adminId: string; email: string; roles: string[]; accessTokenExpiresAt: string }
  | { kind: 'anonymous' }
  | { kind: 'unreachable' }

interface PlatformSessionResponseBody {
  authenticated: boolean
  adminId?: string
  email?: string
  roles?: string[]
  accessTokenExpiresAt?: string
}

/**
 * GET /platform/api/session — a state PROBE, not a protected resource (see
 * the Go handler's own doc comment: it always returns 200). Unlike the
 * tenant-admin equivalent there is no 'denied'/'reauth' outcome — a
 * platform-admin session is either genuinely active or it isn't; when it
 * expires the operator just signs in again.
 */
export async function fetchSession(): Promise<PlatformAdminSessionState> {
  const result = await platformAdminFetch('/session')
  if (result.kind === 'sessionExpired') return { kind: 'anonymous' }

  const { response } = result
  if (!response.ok) return { kind: 'unreachable' }

  const data = (await response.json().catch(() => null)) as PlatformSessionResponseBody | null
  if (!data) return { kind: 'unreachable' }
  if (!data.authenticated) return { kind: 'anonymous' }

  return {
    kind: 'active',
    adminId: data.adminId ?? '',
    email: data.email ?? '',
    roles: data.roles ?? [],
    accessTokenExpiresAt: data.accessTokenExpiresAt ?? '',
  }
}

export type PlatformLoginResult =
  | { kind: 'ok'; state: PlatformAdminSessionState }
  | { kind: 'invalidCredentials' }
  | { kind: 'notPlatformAdmin' }
  | { kind: 'unreachable' }

/**
 * POST /platform/api/login — email/password only, no PKCE, no redirect.
 * `invalidCredentials` is deliberately uninformative (the backend's
 * enumeration-safe `invalid_credentials` forwarded as-is — never "no such
 * admin" / "wrong password"). `notPlatformAdmin` is the "creation alone
 * doesn't grant access" case: real credentials, but the account holds no
 * PLATFORM_ADMIN role, so no session was established.
 */
export async function login(email: string, password: string): Promise<PlatformLoginResult> {
  const result = await platformAdminFetch('/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (result.kind === 'sessionExpired') return { kind: 'unreachable' } // login has no session to expire

  const { response } = result
  if (response.ok) {
    const data = (await response.json().catch(() => null)) as PlatformSessionResponseBody | null
    if (!data?.authenticated) return { kind: 'unreachable' }
    return {
      kind: 'ok',
      state: {
        kind: 'active',
        adminId: data.adminId ?? '',
        email: data.email ?? '',
        roles: data.roles ?? [],
        accessTokenExpiresAt: data.accessTokenExpiresAt ?? '',
      },
    }
  }

  const body = (await response.json().catch(() => null)) as { error?: string } | null
  if (body?.error === 'not_platform_admin') return { kind: 'notPlatformAdmin' }
  if (response.status === 401) return { kind: 'invalidCredentials' }
  return { kind: 'unreachable' }
}

/** POST /platform/api/signout — clears the BFF's own platform session cookie. */
export async function signOut(): Promise<boolean> {
  const result = await platformAdminFetch('/signout', { method: 'POST' })
  if (result.kind === 'sessionExpired') return true
  return result.response.ok
}
