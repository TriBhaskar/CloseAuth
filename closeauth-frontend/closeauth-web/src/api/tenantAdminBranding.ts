// Stage UI-3e: tenant branding — the API layer talking to
// internal/server/handlers_admin_tenant_settings.go's
// /t/{slug}/api/branding GET/PUT pair (the BFF's first PUT routes). Same
// tenantAdminFetch/parseAdminResult shape as every other module in this
// directory.
//
// Two backend contract facts a caller (TenantSettingsView.vue) MUST respect:
//
//  1. PUT is a FULL REPLACEMENT, not a merge — TenantBrandingService.
//     updateBranding sets all five columns unconditionally from the command.
//     A caller must always send all five fields, seeded from the current
//     GET, never a sparse object — omitting a field clears it (it round-
//     trips through blankToNull on the backend), exactly like
//     tenantAdminResourceServers.ts's UpdateScopePayload.
//  2. Default-pinning is unavoidable and must be disclosed, not hidden. GET
//     resolves an unset color to the platform default (e.g. #4F46E5) — the
//     response gives NO way to tell "tenant explicitly set this color" from
//     "inheriting the platform default". So the FIRST save an admin makes
//     (even one that only touches logoUrl) pins today's platform defaults as
//     this tenant's explicit colors forever after — there is no backend
//     affordance to "unset" a color back to inherited once a PUT has
//     happened. This is a real property of the API (flagged in the UI-3e
//     stage report as a backend follow-up candidate, not fixed here) — the
//     settings form must say so in a hint, not paper over it.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'

// Mirrors tenant/dto/BrandingView.java exactly. All five fields are always
// present on the wire (BrandingView is not @JsonInclude(NON_NULL)) — unset
// logoUrl/companyName arrive as "", never absent or null; colors always
// carry a real #RRGGBB value (tenant-set or platform-default).
export interface BrandingView {
  logoUrl: string
  primaryColor: string
  backgroundColor: string
  accentColor: string
  companyName: string
}

// Mirrors tenant/dto/UpdateBrandingCommand.java — every field is optional on
// the wire, but see the file header: this module's callers must always send
// all five (full replacement), so the type below intentionally does NOT mark
// any field optional.
export type UpdateBrandingPayload = BrandingView

export async function getBranding(slug: string): Promise<AdminResult<BrandingView>> {
  const result = await tenantAdminFetch(slug, '/branding')
  return parseAdminResult<BrandingView>(result)
}

export async function updateBranding(slug: string, payload: UpdateBrandingPayload): Promise<AdminResult<BrandingView>> {
  const result = await tenantAdminFetch(slug, '/branding', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<BrandingView>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/**
 * The two logo-url domain codes arrive as a 400 with NO `errors` map (an
 * empty CloseAuthDomainException context — TenantBrandingService.invalid)
 * — parseAdminResult's `error` kind, not `validationErrors`. This maps both
 * onto the logo field so the settings form can land them there instead of a
 * generic banner, using the `code` field problem.ts's `error`
 * kind now carries (UI-3e addition).
 */
export const BRANDING_ERROR_FIELDS: Record<string, string> = {
  'branding.invalid_logo_url': 'logoUrl',
  'branding.logo_url_not_https': 'logoUrl',
}
