// FE-4c: the tenant-wide scope catalog the create-client wizard's Scopes
// step needs — no backend endpoint exists for this (listScopes is per
// resource-server only), so this module composes it client-side: one
// listResourceServers call, then listScopes per RS in parallel. Fine at
// realistic per-tenant resource-server counts; a real bottleneck here would
// warrant a dedicated tenant-wide backend endpoint, not a fix to this file.
import { listResourceServers, listScopes, type ScopeView } from '@/api/tenantAdminResourceServers'

export interface ScopeCatalogGroup {
  resourceServerId: string
  resourceServerName: string
  /** The `{slug}` half of the `{slug}:{scope}` scope string every backend consumer expects — see ScopeSelector.vue. */
  resourceServerSlug: string
  scopes: ScopeView[]
}

export interface ScopeCatalogResult {
  groups: ScopeCatalogGroup[]
  /** true if any resource server's scope page was truncated (more scopes exist than fetched) — a caller can disclose this rather than silently omitting scopes from the selector. */
  truncated: boolean
}

const RS_PAGE_SIZE = 200
const SCOPE_PAGE_SIZE = 200

export async function loadScopeCatalog(slug: string): Promise<ScopeCatalogResult | null> {
  const rsResult = await listResourceServers(slug, 0, RS_PAGE_SIZE)
  if (rsResult.kind !== 'ok') return null

  let truncated = rsResult.value.totalPages > 1
  const groups = await Promise.all(
    rsResult.value.items.map(async (rs): Promise<ScopeCatalogGroup | null> => {
      const scopesResult = await listScopes(slug, rs.id, 0, SCOPE_PAGE_SIZE)
      if (scopesResult.kind !== 'ok') return null
      if (scopesResult.value.totalPages > 1) truncated = true
      return {
        resourceServerId: rs.id,
        resourceServerName: rs.name,
        resourceServerSlug: rs.slug,
        scopes: scopesResult.value.items,
      }
    }),
  )

  const resolved = groups.filter((g): g is ScopeCatalogGroup => g !== null)
  if (resolved.length !== groups.length) return null

  return { groups: resolved, truncated }
}
