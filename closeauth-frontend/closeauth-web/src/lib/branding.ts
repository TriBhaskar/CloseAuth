// Stage UI-3e: the ONE shared home for the "unset logoUrl is empty string,
// never null" truthiness guard — previously only useOAuthTheme.ts's `hasLogo`
// (UI-2a). This stage's tenant-admin settings view (TenantSettingsView.vue)
// needs the identical guard for its live logo preview, so the check moved
// here; FE-1.3 later replaced useOAuthTheme.ts with
// components/common/TenantBrandingProvider.vue, which calls this same guard
// (combined with its own logo-URL safety check) rather than growing a second
// copy — exactly the divergent-copy bug the original codebase snapshot
// flagged for branding logic.
//
// Contract (FEATURES_AND_USE_CASES.md §10, confirmed by IT-10): both the
// public GET /branding and the authenticated admin GET/PUT
// /v1/tenants/{id}/branding resolve an unset logoUrl to "" — an ungated
// `<img src="">` makes the browser re-request the CURRENT PAGE as the image,
// which is not equivalent to omitting the image. Every consumer that binds
// logoUrl into an <img> must gate on this first.
export function hasBrandingLogo(logoUrl: string | null | undefined): boolean {
  return Boolean(logoUrl)
}
