import type { RouteLocationNormalizedLoaded } from 'vue-router'

// BE-B (spec §2.2): every hosted-auth page moved from an unnamespaced root
// path to /t/:slug/... — every view's own internal cross-links (LoginView's
// "Forgot password?"/"Email me a link instead", ForgotPasswordView's "Back
// to sign in", etc.) need the same tenant slug carried forward, or they'd
// silently regress to a route that no longer exists. One shared helper
// rather than each view re-deriving `route.params.slug` its own way.
export function hostedAuthPath(route: RouteLocationNormalizedLoaded, path: string): string {
  const slug = typeof route.params.slug === 'string' ? route.params.slug : ''
  return `/t/${slug}${path}`
}
