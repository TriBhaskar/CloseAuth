import { createRouter, createWebHistory } from 'vue-router'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

// Stage UI-4 adds the platform-admin console's own route tree, `/platform/...`
// — genuinely separate from `/t/{slug}/...` below, never merged into it:
// TENANT_ADMIN and PLATFORM_ADMIN are separate principal types (vision §7.8).
// See the routes and the `requiresPlatformAdmin` guard branch further down.
//
// `/login` here is a CLIENT-SIDE route rendering LoginView.vue — it coexists
// on the same URL path as the BFF's server-side `POST /login` proxy
// (handlers_auth_proxy.go) without conflict: chi only registers that path for
// POST, so a GET here falls through to the SPA catch-all (routes.go's
// `r.NotFound(static.SPAHandler().ServeHTTP)`), which serves this app's
// index.html, and THIS router then matches the path client-side. See the
// stage report for the one open question this leaves (how a real,
// unauthenticated /oauth2/authorize hit ends up navigating a browser to this
// exact path in production — a backend-side wiring question, not something
// this router needs to resolve to be correct on its own terms).
//
// Stage UI-3a settles the `/t/{slug}/...` path-based tenant convention
// (path-based, not subdomain — see the stage plan) and adds this router's
// FIRST navigation guard (below). Tenant-slug validity is loose here
// (structural — the BFF's own validSlug is the source of truth for
// rejecting a bad slug outright); this guard's job is purely "does this
// browser have a usable tenant-admin session for this slug", not
// slug-format validation.
declare module 'vue-router' {
  interface RouteMeta {
    requiresTenantAdmin?: boolean
    requiresPlatformAdmin?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/public/HomeView.vue'),
    },
    {
      path: '/login',
      component: () => import('@/views/auth/LoginView.vue'),
    },
    {
      path: '/register',
      component: () => import('@/views/auth/RegisterView.vue'),
    },
    // Independently reachable (Stage UI-2b, Deliverable 5) — not just an
    // inline step of /register; a user returning later to finish
    // verification (or resend a code) navigates straight here.
    {
      path: '/verify-email',
      component: () => import('@/views/auth/VerifyEmailView.vue'),
    },

    // Stage UI-2c-i, Deliverable 3: magic-link's REQUEST step only — reached
    // via LoginView.vue's "Email me a link instead" link. No route for the
    // consume step (Design Decision #1 — the emailed link points straight
    // at the backend's own origin, never through this SPA).
    {
      path: '/magic-link-request',
      component: () => import('@/views/auth/MagicLinkRequestView.vue'),
    },
    // Stage UI-2c-i, Deliverable 4: password reset's request + confirm
    // steps — /forgot-password reached via LoginView.vue's "Forgot
    // password?" link; /reset-password is the BFF-hosted landing page the
    // emailed reset link points at (PasswordResetService.resetUrl's
    // confirmed `{bffBaseUrl}/reset-password?token=...&client_id=...`
    // shape — see the stage report).
    {
      path: '/forgot-password',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
    },
    {
      path: '/reset-password',
      component: () => import('@/views/auth/ResetPasswordView.vue'),
    },
    // Phase 4a: the BFF-hosted landing page for tenant-onboarding password
    // rotation — PasswordRotationService.rotationPageUrl's confirmed
    // `{bffBaseUrl}/password-rotation?token=...&client_id=...` shape (plus,
    // only on the temp-password on-ramp, `&authorize_query=...`). Public,
    // no meta — same as /reset-password, this must be reachable by an
    // unauthenticated user with no session; the router's only guard
    // (beforeEach below) early-exits for any route without
    // requiresTenantAdmin/requiresPlatformAdmin meta.
    {
      path: '/password-rotation',
      component: () => import('@/views/auth/PasswordRotationView.vue'),
    },

    // Stage UI-2c-ii, Deliverable 2: consent. Reached via the backend's
    // consent-required redirect, which now lands on this exact absolute path
    // (bff.consent-page's corrected `/consent` default — see
    // CLOSEAUTH_CONSENT_BACKEND_REPORT.md) with client_id/scope/state already
    // appended by SAS itself. No route needed for the decision itself — the
    // approve/deny forms this view renders POST straight to the backend's
    // own origin, never back through this SPA.
    {
      path: '/consent',
      component: () => import('@/views/auth/ConsentView.vue'),
    },

    // Stage UI-3a: the tenant-admin console. /t/:slug/console is the ONLY
    // route behind the guard (meta.requiresTenantAdmin) — /denied and
    // /auth-error are deliberately siblings OUTSIDE it, never children of a
    // guarded parent, so landing on a refusal page can never itself
    // re-trigger the guard's redirect-to-login logic (that would recreate
    // the very loop this stage exists to prevent).
    {
      path: '/t/:slug',
      redirect: (to) => `/t/${to.params.slug}/console`,
    },
    {
      path: '/t/:slug/console',
      component: () => import('@/layouts/TenantAdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'tenant-admin-console',
          component: () => import('@/views/tenant-admin/TenantAdminHomeView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        // Stage UI-3b: the console's first real CRUD surface. A
        // deep-linkable detail route (not a modal) is deliberate — it's
        // the list-then-detail pattern every later surface (clients,
        // roles, branding, audit) reuses.
        {
          path: 'users',
          name: 'tenant-admin-users',
          component: () => import('@/views/tenant-admin/TenantUsersView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'users/:userId',
          name: 'tenant-admin-user-detail',
          component: () => import('@/views/tenant-admin/TenantUserDetailView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        // Stage UI-3c: clients (no list — the backend has none, see
        // TenantClientsView.vue) and resource servers + scopes (full CRUD
        // list-then-detail, same pattern as users above).
        // clients/credentials is declared before clients/:clientId for
        // readability; Vue Router ranks static segments over dynamic ones
        // regardless of declaration order, so the two never actually race.
        {
          path: 'clients',
          name: 'tenant-admin-clients',
          component: () => import('@/views/tenant-admin/TenantClientsView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'clients/credentials',
          name: 'tenant-admin-client-credentials',
          component: () => import('@/views/tenant-admin/TenantClientCredentialsView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'clients/:clientId',
          name: 'tenant-admin-client-detail',
          component: () => import('@/views/tenant-admin/TenantClientDetailView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'resource-servers',
          name: 'tenant-admin-resource-servers',
          component: () => import('@/views/tenant-admin/TenantResourceServersView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'resource-servers/:rsId',
          name: 'tenant-admin-resource-server-detail',
          component: () => import('@/views/tenant-admin/TenantResourceServerDetailView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        // Stage UI-3d: tenant-role CRUD (list + dialogs, no detail route —
        // see TenantRolesView.vue's file header for why) and application
        // roles' scope-bundling detail route, nested under the RS whose
        // scopes it bundles — mirrors the backend's own RS-nested URL shape.
        {
          path: 'roles',
          name: 'tenant-admin-roles',
          component: () => import('@/views/tenant-admin/TenantRolesView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'resource-servers/:rsId/roles/:roleId',
          name: 'tenant-admin-application-role-detail',
          component: () => import('@/views/tenant-admin/TenantApplicationRoleDetailView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        // Stage UI-3e: the console's last two surfaces — tenant settings
        // (branding + registration mode, one page, two cards — neither is
        // list-shaped, so neither gets its own list-then-detail pair) and
        // the read-only audit log.
        {
          path: 'settings',
          name: 'tenant-admin-settings',
          component: () => import('@/views/tenant-admin/TenantSettingsView.vue'),
          meta: { requiresTenantAdmin: true },
        },
        {
          path: 'audit',
          name: 'tenant-admin-audit',
          component: () => import('@/views/tenant-admin/TenantAuditView.vue'),
          meta: { requiresTenantAdmin: true },
        },
      ],
    },
    {
      path: '/t/:slug/denied',
      name: 'tenant-admin-denied',
      component: () => import('@/views/tenant-admin/TenantAdminDeniedView.vue'),
    },
    {
      path: '/t/:slug/auth-error',
      name: 'tenant-admin-auth-error',
      component: () => import('@/views/tenant-admin/TenantAdminAuthErrorView.vue'),
    },

    // Stage UI-4: the platform-admin console. Deliberately no slug segment
    // anywhere — this surface is cross-tenant by construction — so it can
    // never collide with the /t/:slug tree above. /platform/login is
    // UNGUARDED (a plain in-SPA form, not an OAuth2 round trip — there is no
    // backend SSO entry point for an anonymous visitor to be redirected to,
    // so "try a different account" is just retyping, unlike the tenant
    // console's denial-marker loop-break). /platform/console is the only
    // guarded subtree (meta.requiresPlatformAdmin).
    {
      path: '/platform',
      redirect: '/platform/console',
    },
    {
      path: '/platform/login',
      name: 'platform-admin-login',
      component: () => import('@/views/platform-admin/PlatformLoginView.vue'),
    },
    {
      path: '/platform/console',
      component: () => import('@/layouts/PlatformAdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'platform-admin-console',
          component: () => import('@/views/platform-admin/PlatformOverviewView.vue'),
          meta: { requiresPlatformAdmin: true },
        },
        {
          path: 'tenants',
          name: 'platform-admin-tenants',
          component: () => import('@/views/platform-admin/PlatformTenantsView.vue'),
          meta: { requiresPlatformAdmin: true },
        },
        {
          path: 'admins',
          name: 'platform-admin-admins',
          component: () => import('@/views/platform-admin/PlatformAdminsView.vue'),
          meta: { requiresPlatformAdmin: true },
        },
      ],
    },

    // ── Catch-all: redirect unknown paths to home ─────────────────────────────
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/

// The repo's first navigation guard. Only routes tagged
// meta.requiresTenantAdmin/meta.requiresPlatformAdmin are gated; everything
// else (including each surface's own denial/error pages) passes through
// unguarded — see the route comments above for why that separation matters.
router.beforeEach(async (to) => {
  if (to.meta.requiresPlatformAdmin) {
    const store = usePlatformAdminSessionStore()
    const state = await store.load()

    switch (state.kind) {
      case 'active':
        return true
      case 'unreachable':
      case 'anonymous':
        // Unlike the tenant branch below, this is a plain client-side
        // navigation, not window.location.assign: there is no backend SSO
        // entry point to reach (platform-admin login is a JSON POST, not an
        // OAuth2 redirect target), so a real top-level navigation buys
        // nothing here — router.push keeps the SPA's own state intact.
        return { name: 'platform-admin-login', query: { returnTo: to.fullPath } }
    }
  }

  if (!to.meta.requiresTenantAdmin) return true

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
})

export default router
