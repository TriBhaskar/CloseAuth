// FE-2a (spec §6.2.1 step 3): the JSON counterpart the tenant resolver calls
// to start a login for the admin-console-{tenantId} client when no session
// exists. Returns the /oauth2/authorize URL as JSON for the CALLER to
// window.location.assign itself — this module must never fetch-follow it.
// Only a genuine top-level browser navigation carries the Accept header SAS
// requires and the SameSite=Lax backend session cookie a fetch() can't
// (see internal/backend/oauth_client.go's AuthorizeURL doc comment, and
// handlers_authorize_start.go's, for the full reasoning on the Go side).
export type AuthorizeStartResult =
  | { kind: 'ok'; authorizeUrl: string }
  | { kind: 'notFound' }
  | { kind: 'rateLimited' }
  | { kind: 'error' }

export async function startAuthorize(tenantId: string): Promise<AuthorizeStartResult> {
  let response: Response
  try {
    response = await fetch('/api/auth/authorize/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // Sets the OAuthContext cookie (Path=/) this flow's callback later
      // reads — must be included, unlike entryResolve.ts's plain existence
      // probe.
      credentials: 'include',
      body: JSON.stringify({ tenantId }),
    })
  } catch {
    return { kind: 'error' }
  }

  if (response.status === 404) return { kind: 'notFound' }
  if (response.status === 429) return { kind: 'rateLimited' }
  if (!response.ok) return { kind: 'error' }

  const data = (await response.json().catch(() => null)) as { authorizeUrl?: string } | null
  if (!data?.authorizeUrl) return { kind: 'error' }
  return { kind: 'ok', authorizeUrl: data.authorizeUrl }
}
