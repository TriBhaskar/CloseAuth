import { createRouter, createWebHistory } from 'vue-router'
import { dispatchGuard } from '@/app/guards'

// Stage UI-4 adds the platform-admin console's own route tree, `/platform/...`
// — genuinely separate from `/t/{slug}/...` below, never merged into it:
// TENANT_ADMIN and PLATFORM_ADMIN are separate principal types (vision §7.8).
// See the routes and guards.ts's `requiresPlatformAdmin` guard.
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
// FIRST navigation guard. FE-1.12 moved that guard's logic to guards.ts
// (spec §9: "app/ — router, guards, providers, app bootstrap") and
// collapsed the two boolean meta flags into one `meta.guard` — absent
// `guard` IS `requiresNone` (spec §7.1's first guard), not a flag set on
// every public route.
declare module 'vue-router' {
  interface RouteMeta {
    /** FE-1.12 (spec §7.1): which route guard applies. Absent = requiresNone. FE-4d adds 'tenantSession'. */
    guard?: 'tenantAdmin' | 'platformAdmin' | 'tenantSession'
    /** FE-1.10: ConsoleShell's topbar page title (spec §4.2, "current page title (left)"). */
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // FE-0.3: HomeView.vue (marketing landing page, out of scope per spec
    // §0.3) was deleted, and a placeholder held this route until FE-2.1
    // built the real workspace entry screen (spec §6.1) here —
    // WorkspaceEntryView.vue, renamed from WorkspaceEntryPlaceholderView.vue
    // once "placeholder" stopped being true. See FRONTEND_INVENTORY.md.
    {
      path: '/',
      component: () => import('@/views/public/WorkspaceEntryView.vue'),
    },
    // BE-B (spec §2.2): every hosted-auth page below moved from an
    // unnamespaced root path to /t/:slug/... — the tenant-namespaced shape
    // spec §2.2 requires and every backend-generated link (SAS's login
    // redirect, PasswordResetService/PasswordRotationService/InviteService's
    // emailed links) now actually points at. `slug` is an available-but-
    // unconsumed route param on every one of these views until FE-2 wires
    // the real per-tenant resolver/branding-by-slug flow — each view still
    // derives `clientId` from the query string exactly as before, so no
    // view's internal logic changed. Old unnamespaced paths are gone
    // outright (no migration aliases — nothing in the spec calls for one;
    // the catch-all below sends a stale bookmark to /not-found).
    {
      path: '/t/:slug/login',
      component: () => import('@/views/auth/LoginView.vue'),
    },
    {
      path: '/t/:slug/register',
      component: () => import('@/views/auth/RegisterView.vue'),
    },
    // Independently reachable (Stage UI-2b, Deliverable 5) — not just an
    // inline step of /register; a user returning later to finish
    // verification (or resend a code) navigates straight here.
    {
      path: '/t/:slug/verify-email',
      component: () => import('@/views/auth/VerifyEmailView.vue'),
    },

    // Stage UI-2c-i, Deliverable 3: magic-link's REQUEST step only — reached
    // via LoginView.vue's "Email me a link instead" link. No route for the
    // consume step (Design Decision #1 — the emailed link points straight
    // at the backend's own origin, never through this SPA; spec §2.2 lists
    // /t/{tenantId}/magic-link/consume too, but that conflict is a recorded,
    // deliberate non-gap — see FRONTEND_INVENTORY.md). BE-B also folds in
    // spec §2.2's own path rename: magic-link-request -> magic-link.
    {
      path: '/t/:slug/magic-link',
      component: () => import('@/views/auth/MagicLinkRequestView.vue'),
    },
    // Stage UI-2c-i, Deliverable 4: password reset's request + confirm
    // steps — /forgot-password reached via LoginView.vue's "Forgot
    // password?" link; /reset-password is the BFF-hosted landing page the
    // emailed reset link points at (PasswordResetService.resetUrl now emits
    // `{bffBaseUrl}/t/{slug}/reset-password?token=...&client_id=...` — BE-B).
    {
      path: '/t/:slug/forgot-password',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
    },
    {
      path: '/t/:slug/reset-password',
      component: () => import('@/views/auth/ResetPasswordView.vue'),
    },
    // Phase 4a: the BFF-hosted landing page for tenant-onboarding password
    // rotation — PasswordRotationService.rotationPageUrl now emits
    // `{bffBaseUrl}/t/{slug}/password-rotation?token=...&client_id=...`
    // (plus, only on the temp-password on-ramp, `&authorize_query=...`) —
    // BE-B. Public, no meta — same as /reset-password, this must be
    // reachable by an unauthenticated user with no session; dispatchGuard
    // (guards.ts) treats any route without meta.guard as requiresNone.
    {
      path: '/t/:slug/password-rotation',
      component: () => import('@/views/auth/PasswordRotationView.vue'),
    },

    // Stage UI-2c-ii, Deliverable 2 / BE-B: consent. SAS can only ever
    // redirect to ONE fixed, un-namespaced URL (verified against the actual
    // vendored Spring Authorization Server 1.5.1 source — consentPage() has
    // no per-request templating), so /consent stays reachable exactly as
    // before: client_id/scope/state appended by SAS itself. ConsentView.vue
    // itself performs a client-side redirect to the /t/:slug/consent
    // sibling below once branding resolves (it now carries tenantSlug) —
    // see that component's header for the two-hop design. Neither route
    // needs the decision itself — the approve/deny forms POST straight to
    // the backend's own origin, never back through this SPA.
    {
      path: '/consent',
      component: () => import('@/views/auth/ConsentView.vue'),
    },
    {
      path: '/t/:slug/consent',
      component: () => import('@/views/auth/ConsentView.vue'),
    },

    // Stage UI-3a: the tenant-admin console. /t/:slug/console is the ONLY
    // route behind the guard (meta.guard === 'tenantAdmin') — /denied and
    // /auth-error are deliberately siblings OUTSIDE it, never children of a
    // guarded parent, so landing on a refusal page can never itself
    // re-trigger the guard's redirect-to-login logic (that would recreate
    // the very loop this stage exists to prevent).
    // FE-2a (spec §6.2.1): the real tenant resolver — was a bare
    // `redirect: (to) => \`/t/${to.params.slug}/console\`` console shortcut
    // (FRONTEND_INVENTORY.md graded that Replace). See TenantResolverView's
    // own header for the full resolve → session-probe → authorize-start
    // logic.
    {
      path: '/t/:slug',
      component: () => import('@/views/public/TenantResolverView.vue'),
    },
    // FE-4d (spec §6.4.8): /account is reachable by every tenant user,
    // admin or not — it uses the SAME TenantAdminLayout.vue/ConsoleShell
    // as /console (whose nav becomes role-conditional, see that file),
    // rather than a separate shell, but keeps its own top-level URL (an
    // empty-path child keeps the path exactly /t/{slug}/account, not
    // nested under /console). Guarded by 'tenantSession' (any valid
    // session), not 'tenantAdmin'.
    {
      path: '/t/:slug/account',
      component: () => import('@/layouts/TenantAdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'tenant-account',
          component: () => import('@/views/tenant-admin/TenantAccountView.vue'),
          meta: { guard: 'tenantSession', title: 'My account' },
        },
      ],
    },
    {
      path: '/t/:slug/logged-out',
      component: () => import('@/views/public/TenantLoggedOutView.vue'),
    },
    {
      path: '/t/:slug/console',
      component: () => import('@/layouts/TenantAdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'tenant-admin-console',
          component: () => import('@/views/tenant-admin/TenantAdminHomeView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Overview' },
        },
        // Stage UI-3b: the console's first real CRUD surface. A
        // deep-linkable detail route (not a modal) is deliberate — it's
        // the list-then-detail pattern every later surface (clients,
        // roles, branding, audit) reuses.
        {
          path: 'users',
          name: 'tenant-admin-users',
          component: () => import('@/views/tenant-admin/TenantUsersView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Users' },
        },
        {
          path: 'users/:userId',
          name: 'tenant-admin-user-detail',
          component: () => import('@/views/tenant-admin/TenantUserDetailView.vue'),
          meta: { guard: 'tenantAdmin', title: 'User' },
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
          meta: { guard: 'tenantAdmin', title: 'Clients' },
        },
        {
          path: 'clients/credentials',
          name: 'tenant-admin-client-credentials',
          component: () => import('@/views/tenant-admin/TenantClientCredentialsView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Client credentials' },
        },
        {
          path: 'clients/:clientId',
          name: 'tenant-admin-client-detail',
          component: () => import('@/views/tenant-admin/TenantClientDetailView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Client' },
        },
        {
          path: 'resource-servers',
          name: 'tenant-admin-resource-servers',
          component: () => import('@/views/tenant-admin/TenantResourceServersView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Resource servers' },
        },
        {
          path: 'resource-servers/:rsId',
          name: 'tenant-admin-resource-server-detail',
          component: () => import('@/views/tenant-admin/TenantResourceServerDetailView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Resource server' },
        },
        // Stage UI-3d: tenant-role CRUD (list + dialogs, no detail route —
        // see TenantRolesView.vue's file header for why) and application
        // roles' scope-bundling detail route, nested under the RS whose
        // scopes it bundles — mirrors the backend's own RS-nested URL shape.
        {
          path: 'roles',
          name: 'tenant-admin-roles',
          component: () => import('@/views/tenant-admin/TenantRolesView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Roles' },
        },
        {
          path: 'resource-servers/:rsId/roles/:roleId',
          name: 'tenant-admin-application-role-detail',
          component: () => import('@/views/tenant-admin/TenantApplicationRoleDetailView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Application role' },
        },
        // Stage UI-3e: the console's last two surfaces — tenant settings
        // (branding + registration mode, one page, two cards — neither is
        // list-shaped, so neither gets its own list-then-detail pair) and
        // the read-only audit log.
        {
          path: 'settings',
          name: 'tenant-admin-settings',
          component: () => import('@/views/tenant-admin/TenantSettingsView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Settings' },
        },
        {
          path: 'audit',
          name: 'tenant-admin-audit',
          component: () => import('@/views/tenant-admin/TenantAuditView.vue'),
          meta: { guard: 'tenantAdmin', title: 'Audit log' },
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
    // guarded subtree (meta.guard === 'platformAdmin').
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
          meta: { guard: 'platformAdmin', title: 'Overview' },
        },
        {
          path: 'tenants',
          name: 'platform-admin-tenants',
          component: () => import('@/views/platform-admin/PlatformTenantsView.vue'),
          meta: { guard: 'platformAdmin', title: 'Tenants' },
        },
        // FE-3b: list-then-detail, the same pattern the tenant console's
        // users route already established (tenant-admin-user-detail).
        {
          path: 'tenants/:tenantId',
          name: 'platform-admin-tenant-detail',
          component: () => import('@/views/platform-admin/PlatformTenantDetailView.vue'),
          meta: { guard: 'platformAdmin', title: 'Tenant' },
        },
        {
          path: 'admins',
          name: 'platform-admin-admins',
          component: () => import('@/views/platform-admin/PlatformAdminsView.vue'),
          meta: { guard: 'platformAdmin', title: 'Platform admins' },
        },
      ],
    },

    // FE-1 checkpoint: dev-only component gallery. The spread-conditional
    // means this route object doesn't exist in the routes array AT ALL in a
    // production build (import.meta.env.DEV is statically false, so Vite
    // dead-code-eliminates the whole branch) — not just hidden behind a
    // runtime check a curious visitor could bypass.
    ...(import.meta.env.DEV
      ? [
          {
            path: '/dev/gallery',
            name: 'dev-component-gallery',
            component: () => import('@/views/dev/ComponentGalleryView.vue'),
          },
        ]
      : []),

    // FE-1.12 (spec route map, §7.3): public, unguarded terminal pages.
    // /not-found is where the catch-all below lands; /error is the app's
    // last-resort landing (see ErrorView.vue's own header for the
    // distinction and current trace_id limitation).
    {
      path: '/not-found',
      name: 'not-found',
      component: () => import('@/views/public/NotFoundView.vue'),
    },
    {
      path: '/error',
      name: 'error',
      component: () => import('@/views/public/ErrorView.vue'),
    },

    // ── Catch-all: an unknown URL is a 404, not a silent bounce home ──────────
    { path: '/:pathMatch(.*)*', redirect: '/not-found' },
  ],
})

// The repo's first navigation guard, moved to guards.ts (FE-1.12) as a
// single dispatcher keyed on meta.guard — see that file for the guard
// implementations and the still-unbuilt fourth guard.
router.beforeEach(dispatchGuard)

export default router
