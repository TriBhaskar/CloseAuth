// FE-1.12 (spec §7.1, §9): the route guards, split out of router.ts —
// §9's file tree names "app/ — router, guards, providers, app bootstrap".
// One function per implemented guard; `dispatchGuard` is the single
// `beforeEach` wired onto the router in router.ts. A guard failure is
// ALWAYS a redirect (a RouteLocationRaw) or `false` (a real top-level
// navigation already happened) — never a silent pass-through to a
// rendered-then-blanked screen, per §7.1's own rule.
//
// Spec §7.1 names FOUR guards. All four are implemented here:
//   - requiresNone: the default. Absent `meta.guard` IS this guard, not a
//     flag set on every one of the ~10 public routes.
//   - requiresTenantAdmin: wired below.
//   - requiresPlatformAdmin: wired below, unchanged.
//   - requiresTenantSession(tenantId) — "a logged-in tenant user, admin
//     role NOT required" — FE-4d: built. Its blocker was never really the
//     two BFF capabilities this comment used to name (a root `GET
//     /api/session`; a tenant-session probe that tolerates a non-admin
//     session) — `GET /t/{slug}/api/session` already reported everything
//     needed (tenantRoles, possibly empty) whenever authenticated:true. The
//     REAL blocker was one layer earlier: `handleAdminCallback` refused to
//     even CREATE a session for a non-admin tenant user in the first place.
//     Fixed (internal/server/handlers_admin_auth.go) — the callback now
//     only checks client_id/tenant_id binding, not TENANT_ADMIN, for
//     session creation. Existing admin-CRUD routes are untouched
//     (RequireAdminSession independently re-checks IsTenantAdmin() on every
//     request), so this guard is the only NEW thing here.
//
//     A consequence, not a new rule: requiresTenantAdmin's own 'active'
//     case can no longer assume admin — 'active' now legitimately includes
//     non-admin sessions too, so it must check the role explicitly and
//     redirect a non-admin to /account rather than letting them through to
//     an admin-guarded route (see below).
import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

// Bug fix (found via manual testing against a real ten_-prefixed tenant):
// this pattern originally had no ten_-prefixed alternative at all, so a
// real post-BE-A tenant slug (e.g. "ten_rohit", underscore included) failed
// this check and got redirected to auth-error before a session lookup was
// ever attempted — the exact same bug, and the exact same fix, as the Go
// BFF's validSlug (internal/server/handlers_admin_auth.go). Kept permissive
// on the legacy bare-alnum-hyphen shape too (still used throughout this
// codebase's own test fixtures, e.g. 'acme') rather than narrowing to
// ten_-only, for the same reason the Go side stayed permissive: narrowing
// now would be unrelated, high-risk mechanical churn across many spec
// files, not required for correctness (no real tenant can ever be
// bare-shaped after BE-A). This is a loose "well-formed enough to attempt a
// session lookup for" guard — NOT spec §1.2's own strict entry-field
// validator (lib/tenantId.ts's isValidTenantId, a different purpose).
const SLUG_PATTERN = /^(ten_[a-z0-9][a-z0-9-]{1,45}|[a-z0-9][a-z0-9-]{0,62})$/

/**
 * §7.1's `requiresPlatformAdmin`. No slug, no denied/reauth outcome —
 * `PlatformAdminSessionState` is only active/anonymous/unreachable, since a
 * platform-admin session either genuinely works or the operator signs in
 * again (see platformAdminSession.ts).
 */
export async function requiresPlatformAdmin(to: RouteLocationNormalized): Promise<RouteLocationRaw | true> {
  const store = usePlatformAdminSessionStore()
  const state = await store.load()

  switch (state.kind) {
    case 'active':
      return true
    case 'unreachable':
    case 'anonymous':
      // Plain client-side navigation, not window.location.assign: there is
      // no backend SSO entry point to reach (platform-admin login is a
      // JSON POST, not an OAuth2 redirect target), so a real top-level
      // navigation buys nothing here — router.push keeps SPA state intact.
      return { name: 'platform-admin-login', query: { returnTo: to.fullPath } }
  }
}

/**
 * §7.1's `requiresTenantAdmin(tenantId)`. Tenant-slug shape validity is
 * loose here (structural — the BFF's own validSlug is the source of truth
 * for rejecting a bad slug outright); this guard's job is purely "does this
 * browser have a usable tenant-admin session for this slug."
 */
export async function requiresTenantAdmin(to: RouteLocationNormalized): Promise<RouteLocationRaw | true | false> {
  const slug = typeof to.params.slug === 'string' ? to.params.slug : ''
  if (!SLUG_PATTERN.test(slug)) {
    return { name: 'tenant-admin-auth-error', params: { slug: 'unknown' }, query: { reason: 'invalid_slug' } }
  }

  const store = useTenantAdminSessionStore()
  const state = await store.load(slug)

  switch (state.kind) {
    case 'active':
      // FE-4d: 'active' no longer implies admin — the callback fix lets a
      // non-admin tenant user reach a real session too. A non-admin never
      // gets to render an admin-guarded route (§7.1: never render-then-
      // blank); they're redirected to their own account page instead.
      return state.tenantRoles.includes('TENANT_ADMIN')
        ? true
        : { name: 'tenant-account', params: { slug } }
    case 'denied':
      return { name: 'tenant-admin-denied', params: { slug }, query: { reason: state.reason } }
    case 'unreachable':
      return { name: 'tenant-admin-auth-error', params: { slug }, query: { reason: 'bff_unreachable' } }
    case 'reauth':
    case 'anonymous': {
      // A real top-level navigation, NOT a fetch()/router.push(): the
      // backend's SSO entry point is registered only for Accept: text/html,
      // and CLOSEAUTH_SESSION is SameSite=Lax — neither survives anything
      // but a genuine browser navigation (see AuthorizeURL's doc comment on
      // the Go side for the full reasoning).
      const path = state.kind === 'reauth' ? 'reauth' : 'login'
      window.location.assign(`/t/${slug}/admin/${path}?returnTo=${encodeURIComponent(to.fullPath)}`)
      return false
    }
  }
}

/**
 * §7.1's `requiresTenantSession(tenantId)` — a logged-in tenant user, admin
 * role NOT required. Identical shape to requiresTenantAdmin above, minus the
 * role check: 'active' always passes here, admin or not, since this guard's
 * only consumer (/t/{slug}/account, FE-4.14) is deliberately open to every
 * tenant user by spec (§6.4.8).
 */
export async function requiresTenantSession(to: RouteLocationNormalized): Promise<RouteLocationRaw | true | false> {
  const slug = typeof to.params.slug === 'string' ? to.params.slug : ''
  if (!SLUG_PATTERN.test(slug)) {
    return { name: 'tenant-admin-auth-error', params: { slug: 'unknown' }, query: { reason: 'invalid_slug' } }
  }

  const store = useTenantAdminSessionStore()
  const state = await store.load(slug)

  switch (state.kind) {
    case 'active':
      return true
    case 'denied':
      return { name: 'tenant-admin-denied', params: { slug }, query: { reason: state.reason } }
    case 'unreachable':
      return { name: 'tenant-admin-auth-error', params: { slug }, query: { reason: 'bff_unreachable' } }
    case 'reauth':
    case 'anonymous': {
      const path = state.kind === 'reauth' ? 'reauth' : 'login'
      window.location.assign(`/t/${slug}/admin/${path}?returnTo=${encodeURIComponent(to.fullPath)}`)
      return false
    }
  }
}

/**
 * The single `beforeEach` dispatcher, wired onto the router in router.ts.
 * Only routes tagged `meta.guard` are gated; everything else (including
 * each surface's own denial/error pages) passes through unguarded — see
 * router.ts's route comments for why that separation matters (a refusal
 * page landing behind its own guard could re-trigger the very redirect loop
 * this exists to prevent).
 */
export async function dispatchGuard(to: RouteLocationNormalized): Promise<RouteLocationRaw | boolean> {
  if (to.meta.guard === 'platformAdmin') return requiresPlatformAdmin(to)
  if (to.meta.guard === 'tenantAdmin') return requiresTenantAdmin(to)
  if (to.meta.guard === 'tenantSession') return requiresTenantSession(to)
  return true // requiresNone
}
