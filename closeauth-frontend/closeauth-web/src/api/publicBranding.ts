// FE-1.11: the tenant branding fetch, moved out of TenantBrandingProvider.vue
// (FE-1.3) so no component-level fetch survives (spec §9 rule 2 — "all BFF
// calls go through api/"). Unauthenticated, credentials:'omit', root-path —
// doesn't fit tenantAdminFetch/platformAdminFetch's console-session shape,
// so this is a small standalone module, same pattern as the six auth*.ts
// hosted-auth modules. Never throws — the house discriminated-union
// convention every src/api/* module here follows.
//
// Hex-colour/https-URL validation stays in the COMPONENT, not here — it
// governs what actually reaches a CSS custom property (the security
// boundary TenantBrandingProvider.spec.ts's malicious-payload test asserts
// against), so moving it here would just relocate the boundary without
// simplifying anything.
export interface Branding {
  logoUrl: string
  primaryColor: string
  backgroundColor: string
  accentColor: string
  companyName: string
  /**
   * BE-B: the tenant's public Tenant ID (spec §1.2) — already visible in the
   * hosted-auth page's own URL (`/t/{slug}/...`) once branding resolves.
   * Used by ConsentView.vue's two-hop redirect (SAS can only ever land the
   * browser on the fixed, un-namespaced `/consent`) to route to
   * `/t/{slug}/consent`. `null` for platform-default branding (unknown
   * `client_id` — no tenant to namespace a redirect under).
   */
  tenantSlug: string | null
  /**
   * FE-2b (spec §6.2.2): mirrors tenant/enums/RegistrationMode.java exactly
   * (`'OPEN' | 'EMAIL_VERIFIED' | 'ADMIN_APPROVED' | 'INVITE_ONLY'`) — kept
   * as `string` rather than a literal union here since this module has no
   * other backend-enum-mirroring precedent to follow (unlike
   * api/tenantAdminRegistrationConfig.ts's authenticated counterpart, which
   * does define the literal union — this module's callers only ever do an
   * equality check, never exhaustively switch). `null` for platform-default
   * branding (no tenant to resolve a mode for) — same "no confirmed tenant"
   * signal as `tenantSlug: null`.
   */
  registrationMode: string | null
}

export type BrandingResult = { kind: 'ok'; value: Branding } | { kind: 'error' }

// Module-level cache, keyed by client_id — "fetch once per page-load" means
// once per (client_id, page load), not once per component instance. A
// failed fetch is evicted so a later mount (or theme/route change) can
// retry rather than being stuck on a permanently-cached failure.
const cache = new Map<string, Promise<BrandingResult>>()

async function doFetch(clientId: string): Promise<BrandingResult> {
  try {
    const query = clientId ? `?client_id=${encodeURIComponent(clientId)}` : ''
    const response = await fetch(`/branding${query}`, { credentials: 'omit' })
    if (!response.ok) return { kind: 'error' }
    return { kind: 'ok', value: (await response.json()) as Branding }
  } catch {
    return { kind: 'error' }
  }
}

export async function fetchBranding(clientId: string): Promise<BrandingResult> {
  let pending = cache.get(clientId)
  if (!pending) {
    pending = doFetch(clientId)
    cache.set(clientId, pending)
  }
  const result = await pending
  if (result.kind === 'error') cache.delete(clientId)
  return result
}
