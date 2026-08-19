// FE-2a (spec §6.1, §6.2.1): the tenant-existence oracle both the entry
// screen (before router.push('/t/{tenantId}')) and the tenant resolver
// (reached directly — a bookmark, a shared link, not only via `/`) call.
// Unauthenticated, credentials:'omit', root-path — same standalone-module
// shape as publicBranding.ts, not tenantAdminFetch/platformAdminFetch's
// console-session shape.
export interface EntryResolution {
  tenantId: string
  displayName: string
  status: string
}

export type EntryResolveResult =
  | { kind: 'ok'; value: EntryResolution }
  // Unknown, suspended, deleted, AND rate-limited all collapse into this one
  // outcome — EntryController.java and the BFF's rate limiter (routes.go)
  // are both deliberately byte-identical 404s (spec §6.1's "must be
  // shape-identical for unknown, suspended, and deleted"), so this module
  // can't distinguish them even if it wanted to, and callers must not try.
  | { kind: 'notFound' }
  // Network failure or an unexpected non-404 error — kept distinct from
  // notFound so the UI never claims "workspace not found" when it actually
  // just couldn't ask.
  | { kind: 'error' }

export async function resolveTenant(tenantId: string): Promise<EntryResolveResult> {
  let response: Response
  try {
    response = await fetch(`/api/entry/resolve?tenantId=${encodeURIComponent(tenantId)}`, {
      credentials: 'omit',
    })
  } catch {
    return { kind: 'error' }
  }

  if (response.status === 404) return { kind: 'notFound' }
  if (!response.ok) return { kind: 'error' }

  const data = await response.json().catch(() => null)
  if (!data) return { kind: 'error' }
  return { kind: 'ok', value: data as EntryResolution }
}
