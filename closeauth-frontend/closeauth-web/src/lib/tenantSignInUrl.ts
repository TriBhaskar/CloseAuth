// FE-3b (spec §6.3.2, §6.3.3): the full, absolute sign-in URL for a tenant —
// shown with a copy button on the bootstrap-success hand-off panel and on
// the tenant detail page's header. The platform console IS served from the
// BFF's own origin, so window.location.origin is already the right base —
// no config lookup needed, unlike lib/hostedAuthPath.ts (which reads
// route.params.slug and is useless here; the platform console has no slug
// route param).
export function tenantSignInUrl(slug: string): string {
  return `${window.location.origin}/t/${slug}/login`
}
