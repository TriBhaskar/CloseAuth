package server

import (
	"encoding/json"
	"net/http"
	"time"

	"closeauth-frontend/internal/middleware"
	"closeauth-frontend/internal/static"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

// RegisterRoutes wires up the chi router.
//
// Stage UI-0 left this as a minimal skeleton (the pre-refactor proxy/auth/
// admin handlers were deleted against a stale backend contract — see
// CLOSEAUTH_FRONTEND_BFF_SNAPSHOT.md / UI_STAGE_0_REPORT.md). Stage UI-1
// reintroduces the FIRST real piece of Surface 1: a login/logout proxy pair
// (handlers_auth_proxy.go) built on the generic relay in internal/proxy.
// Deliberately no CSRF middleware wraps this pair — see
// handlers_auth_proxy.go's doc comment for the reasoning.
//
// Stage UI-2a adds two more pieces of Surface 1 on the SAME no-CSRF, no-
// BFF-session pattern: a pure-relay GET /branding (handlers_branding_proxy.go)
// and — genuinely new, not just "more of the same pattern" — the JSON/fetch
// login translation mode at POST /api/auth/login (handlers_login_json.go),
// which exists alongside POST /login (unchanged) rather than replacing it;
// see that file's doc comment for why a distinct route was chosen over an
// Accept-header switch on /login itself.
//
// Stage UI-2b adds registration, email verification, and invite acceptance —
// three more pure-relay routes (handlers_registration_proxy.go), same
// pattern, still no translation needed (none of them ever redirect either).
//
// Stage UI-2c-i adds magic-link's request step and password reset's request
// + confirm steps — same pure-relay pattern, still no translation needed
// (none of the three ever redirect either). Magic-link's GET
// /magic-link/consume deliberately has NO route here — it's a real browser
// navigation straight to the backend's own origin, never through the BFF
// (Design Decision #1, CLOSEAUTH_UI_STAGE_2c-i_PROMPT.md).
//
// Stage UI-2c-ii adds consent's context-fetch proxy, GET /oauth2/consent
// (handlers_consent_proxy.go) — NOT a pure relay like its siblings above: it
// decodes the backend's ConsentContext JSON, injects an authorizeUrl field
// (the backend's real, absolute /oauth2/authorize — computed from
// BackendConfig, never a new backend field), and re-encodes before returning
// it. The consent DECISION (approve/deny) has no route here at all, and never
// will — it's a genuine native HTML form POST straight to the backend's own
// /oauth2/authorize, exactly like magic-link's consume step, for the same
// cross-origin-cookie reason (see CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md).
// Stage UI-3a adds Surface 2, the tenant-admin console's login foundation:
// tenant-scoped routing (/t/{slug}/...), the OAuth2 authorization-code+PKCE
// round trip against the per-tenant admin-console-{slug} client (Option A),
// a real BFF-held session (unlike Surface 1's stateless relay above), and
// lazy silent re-authorization. GET /api/csrf and the /t/{slug} route group
// below are that surface's entire footprint.
//
// Stage UI-3b adds the console's first CRUD surface: users and tenant
// roles (handlers_admin_users.go). Stage UI-3c adds the second: clients
// (handlers_admin_clients.go — register, get, and secret regeneration; no
// list, since the backend has none) and resource servers + their scope
// catalog (handlers_admin_resource_servers.go — full CRUD, the backend
// exposes it all).
//
// Stage UI-3d completes the RBAC surface: tenant-role CRUD
// (handlers_admin_tenant_roles.go — GET /roles and the assign/revoke/
// held-names trio already existed from UI-3b) and the whole application-role
// tier (handlers_admin_application_roles.go — RS-scoped CRUD, scope-bundle
// add/remove, and assign/revoke/held-names for a user). The held-names read
// for application roles required a small backend addition
// (ApplicationRoleController.applicationRolesForUser), the same kind of
// agreed exception as UI-3b's tenant-roles GET.
//
// Stage UI-3e closes out the tenant-admin console's CRUD surface: branding
// (handlers_admin_tenant_settings.go — GET/PUT, the BFF's first PUT
// handlers; AdminClient.PutJSON already existed, used only by
// testsupport.Fixtures.SetRegistrationMode before this stage), registration
// config (same file, same GET/PUT shape), and the read-only audit query
// (handlers_admin_audit.go — GET only, six allow-listed filters plus
// page/size, see that file's auditFilterQuery).
//
// Deliberately NOT in UI-3e or earlier: a platform-admin console (that's
// UI-4, immediately below), and the invites surface (INVITE_ONLY registration
// mode exists and is settable via this stage's registration-config PUT, but
// issuing/listing invites had no console route until FE-4a, below).
//
// Revised: sign-out DOES now end the backend's whole SSO session, not just
// the BFF's console session — the product decision changed after UI-4b
// shipped (tenant-admin login was never meant to behave like end-user SSO).
// GET /t/{slug}/admin/logout (handleAdminLogout, handlers_admin_auth.go) is
// the real "Sign out" action: a top-level navigation through the backend's
// own GET /logout (LogoutController's four-leg cascade), because
// CLOSEAUTH_SESSION is SameSite=Lax and no fetch()/POST from this origin can
// ever carry it. POST /t/{slug}/api/signout (handlers_admin_session.go's
// handleAdminSignOut) still exists as a BFF-cookies-only primitive, but the
// SPA no longer uses it as the sign-out button's action.
//
// Stage UI-4 adds the platform-admin console — genuinely NOT a bigger UI-3:
// platform admins are a separate principal type (platform_admins table,
// Stage 7a), not a role on a tenant user. Route tree is /platform/**, with
// NO slug segment at all (this surface is cross-tenant by construction), so
// it structurally cannot collide with /t/{slug}/**, and its own session
// cookie (bff_platform_session, Path=/platform) is disjoint from
// bff_admin_session's /t/{slug} paths — a browser can hold both. Login is a
// plain JSON POST /platform/api/login against the backend's one
// unauthenticated /v1 endpoint (no OAuth2/PKCE dance, no browser-navigation
// redirect), so — unlike the /t/{slug} group above — there is no
// /platform/login *server* route: the SPA's own client-side route renders
// the login form and calls the API directly. The platform-admin token is
// access-only with a 5-minute TTL and no refresh
// (PlatformAdminTokenService), so RequirePlatformSession
// (internal/middleware/platform_guard.go) has no reauth branch at all —
// on expiry the operator simply signs in again. See
// handlers_platform_auth.go / handlers_platform_tenants.go /
// handlers_platform_admins.go.
func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	// FE-2a: RealIP must run before anything that reads r.RemoteAddr for the
	// caller's actual IP (internal/middleware/ratelimit.go's clientIP) — it
	// rewrites RemoteAddr from X-Forwarded-For/X-Real-IP so every later
	// middleware/handler sees the same normalized value, whether or not the
	// BFF sits behind a proxy.
	r.Use(chimw.RealIP)
	// Skips /assets/* static-chunk requests — see AccessLogger's own doc
	// comment (found during manual testing: those buried the real
	// request-log signal).
	r.Use(middleware.AccessLogger)
	r.Use(chimw.Recoverer)

	// CORS — allow Vue dev server and same-origin in production.
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// ──────────────────────────────────────────────────────────────────────────
	// Public API routes
	// ──────────────────────────────────────────────────────────────────────────
	r.Get("/api/health", s.handleHealthCheck)

	// FE-2a (spec §6.1): the workspace-entry resolution endpoint backing the
	// `/` "Sign in to your workspace" screen — see handlers_entry_proxy.go.
	// Rate-limited per IP (spec's own words: "the BFF must rate-limit it per
	// IP", 20/min suggested) — a limit-exceeded request gets the EXACT SAME
	// 404 shape as an unknown tenant (EntryController's own 404-for-anything-
	// but-ACTIVE contract), never a distinguishing 429: that would itself
	// leak that something is behind the throttle, re-opening the
	// tenant-existence oracle spec §6.1 explicitly closes.
	entryResolveLimiter := middleware.NewIPRateLimiter(20, time.Minute)
	r.With(entryResolveLimiter.Middleware(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})).Get("/api/entry/resolve", s.handleEntryResolveProxy)

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 1 — hosted end-user auth pages: pure relay to the real backend,
	// no BFF-side session/CSRF state (see handlers_auth_proxy.go).
	// ──────────────────────────────────────────────────────────────────────────
	// GET /login is a real full-page browser navigation, not a fetch(): it's
	// where closeauth.bff.login-page sends the browser when SAS's
	// unauthenticated-entry-point redirect fires (see this file's header
	// comment, "Cross-origin login continuity" in CLAUDE.md) — including for
	// the admin-console client's /oauth2/authorize hits, not just Surface 1's
	// hosted end-user login. Without this, chi matches the "/login" node
	// registered below for POST and returns 405 instead of falling through
	// to the SPA catch-all, since NotFound only fires when NO method matches
	// the path at all.
	r.Get("/login", static.SPAHandler().ServeHTTP)
	r.Post("/login", s.handleLoginProxy)
	r.Post("/logout", s.handleLogoutProxy)
	r.Get("/branding", s.handleBrandingProxy)

	// Stage UI-2b, Deliverable 1: registration, verification — see
	// handlers_registration_proxy.go.
	r.Post("/register", s.handleRegisterProxy)
	r.Post("/verify-email/request", s.handleVerifyEmailRequestProxy)
	r.Post("/verify-email/confirm", s.handleVerifyEmailConfirmProxy)

	// Stage UI-2c-i, Deliverable 1: magic-link's request step (see
	// handlers_magic_link_proxy.go — the consume step deliberately has no
	// route here, Design Decision #1) and password reset's request + confirm
	// steps (handlers_password_reset_proxy.go).
	r.Post("/magic-link/request", s.handleMagicLinkRequestProxy)
	r.Post("/password-reset/request", s.handlePasswordResetRequestProxy)
	r.Post("/password-reset/confirm", s.handlePasswordResetConfirmProxy)

	// Stage UI-2c-ii, Deliverable 1: consent's context-fetch proxy only (see
	// handlers_consent_proxy.go) — the decision-submission is deliberately NOT
	// wired here at all; see that file's doc comment.
	r.Get("/oauth2/consent", s.handleConsentContextProxy)

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 1 — JSON/fetch login mode (Stage UI-2a, Deliverable 1): the
	// redirect-vs-JSON-error translation. Distinct from POST /login above —
	// see handlers_login_json.go's doc comment.
	// ──────────────────────────────────────────────────────────────────────────
	r.Post("/api/auth/login", s.handleLoginJSON)

	// Phase 4a: tenant-onboarding password rotation's confirm step — the
	// same redirect-vs-JSON-error translation as /api/auth/login above,
	// because /password-rotation/confirm also returns a 302 + Set-Cookie on
	// success (unlike /password-reset/confirm's plain 200, which is why that
	// route above is a bare relay and this one isn't). See
	// handlers_password_rotation_proxy.go's doc comment.
	r.Post("/api/auth/password-rotation/confirm", s.handlePasswordRotationConfirm)

	// FE-2a (spec §6.2.1 step 3): the unauthenticated tenant resolver's
	// authorize-start — see handlers_authorize_start.go. Rate-limited (its
	// own limiter, not shared with /api/entry/resolve's — a distinct abuse
	// vector, minting OAuth contexts rather than probing tenant existence)
	// since it's an unauthenticated endpoint with real per-call cost
	// (preflightTenant's own backend round trip on every call).
	authorizeStartLimiter := middleware.NewIPRateLimiter(20, time.Minute)
	r.With(authorizeStartLimiter.Middleware(func(w http.ResponseWriter, r *http.Request) {
		writeJSONError(w, http.StatusTooManyRequests, "rate_limited", "Too many attempts. Try again in a moment.")
	})).Post("/api/auth/authorize/start", s.handleAuthorizeStart)

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 2 — tenant-admin console (Stage UI-3a): the BFF as a real OAuth2
	// client. GET /api/csrf lives at the root (settled decision: the CSRF
	// cookie is Path=/, tenant-agnostic, and not an authn credential) — it
	// finally gives src/api/client.ts's existing fetchCsrfToken() call a real
	// route instead of a silently-swallowed 404.
	// ──────────────────────────────────────────────────────────────────────────
	bffCfg := s.bffConfig()
	slugParam := func(r *http.Request) string { return chi.URLParam(r, "slug") }

	r.Get("/api/csrf", middleware.HandleCSRFToken(bffCfg.IsProduction))

	r.Route("/t/{slug}", func(tr chi.Router) {
		tr.Use(middleware.NoCacheMiddleware)

		// Login/reauth initiation — both a real top-level browser navigation
		// (never fetch()): TenantSessionSsoFilter's unauthenticated entry
		// point is registered only for Accept: text/html, and
		// CLOSEAUTH_SESSION is SameSite=Lax, neither of which survives a
		// same-process HTTP call. See handlers_admin_auth.go.
		tr.Get("/admin/login", s.handleAdminAuthStart)
		tr.Get("/admin/reauth", s.handleAdminAuthStart)

		// A real top-level navigation too, for the same SameSite=Lax reason —
		// see handleAdminLogout's doc comment. This is the console's actual
		// "Sign out" action; POST /api/signout below only ever clears the
		// BFF's own cookies and cannot reach CLOSEAUTH_SESSION at all.
		tr.Get("/admin/logout", s.handleAdminLogout)

		tr.Route("/api", func(ar chi.Router) {
			ar.Use(middleware.CSRFTokenMiddleware(bffCfg.IsProduction))
			ar.Use(middleware.CSRFValidationMiddleware)

			// A state probe, not a protected resource — always 200 (see
			// handlers_admin_session.go), so the SPA's router guard never
			// has to special-case "not logged in" as an error.
			ar.Get("/session", s.handleAdminSession)
			ar.Post("/signout", s.handleAdminSignOut)
			ar.Post("/denied/dismiss", s.handleAdminDeniedDismiss)

			// FE-4d: the self-service surface behind /account (spec
			// §6.4.8) — a SEPARATE, weaker gate (any valid tenant
			// session, admin or not) from the pr.Group below (admin-CRUD
			// only). See handlers_me_proxy.go's own header comment.
			ar.Group(func(mr chi.Router) {
				mr.Use(middleware.RequireTenantSession(slugParam, bffCfg.ReauthSkew))
				mr.Get("/me", s.handleMeGet)
				mr.Get("/me/sessions", s.handleMeSessionsList)
				mr.Delete("/me/sessions/{sessionId}", s.handleMeSessionRevoke)
				mr.Post("/me/change-password", s.handleMeChangePassword)
			})

			ar.Group(func(pr chi.Router) {
				pr.Use(middleware.RequireAdminSession(slugParam, bffCfg.ReauthSkew))
				pr.Get("/ping", s.handleAdminPing)

				// Stage UI-3b: the console's first real CRUD surface — user
				// listing/creation/lifecycle and tenant-role assignment. See
				// handlers_admin_users.go's doc comment for the shared
				// session/tenant-scoping/UUID-validation preamble every
				// handler here follows.
				pr.Get("/users", s.handleAdminUsersList)
				pr.Post("/users", s.handleAdminUserCreate)
				// FE-4a: the temporary-password create mode (spec §6.4.2) — a
				// distinct endpoint from POST /users above, not a flag on it.
				pr.Post("/users/with-temp-credential", s.handleAdminUserCreateWithTempCredential)
				pr.Get("/users/{userId}", s.handleAdminUserGet)
				pr.Post("/users/{userId}/suspend", s.handleAdminUserLifecycle("suspend"))
				pr.Post("/users/{userId}/activate", s.handleAdminUserLifecycle("activate"))
				pr.Post("/users/{userId}/approve", s.handleAdminUserLifecycle("approve"))
				pr.Delete("/users/{userId}", s.handleAdminUserDelete)
				pr.Get("/users/{userId}/tenant-roles", s.handleAdminUserTenantRoles)
				pr.Post("/users/{userId}/tenant-roles/{roleId}", s.handleAdminUserRoleAssignment(http.MethodPost))
				pr.Delete("/users/{userId}/tenant-roles/{roleId}", s.handleAdminUserRoleAssignment(http.MethodDelete))
				pr.Get("/roles", s.handleAdminRolesList)

				// FE-4a: the user detail page's Sessions tab — device list,
				// per-session revoke, and revoke-all (a single backend call, not
				// a loop). See handlers_admin_user_sessions.go.
				pr.Get("/users/{userId}/sessions", s.handleAdminUserSessionsList)
				pr.Delete("/users/{userId}/sessions/{sessionId}", s.handleAdminUserSessionRevoke)
				pr.Delete("/users/{userId}/sessions", s.handleAdminUserSessionsRevokeAll)

				// FE-4a: the invitation create mode (spec §6.4.2) — Java's
				// InviteController already existed; this is the first console
				// route reaching it (this file's own header comment used to flag
				// this as deliberately absent — UI-3e's own list, above). See
				// handlers_admin_invites.go.
				pr.Get("/invites", s.handleAdminInvitesList)
				pr.Post("/invites", s.handleAdminInviteCreate)
				pr.Delete("/invites/{inviteId}", s.handleAdminInviteDelete)

				// Stage UI-3c: clients (create/get/regenerate-secret) and resource
				// servers + scopes (full CRUD). FE-4.10 added the client list once
				// the backend gained one. Client update/delete added once
				// TenantClientController gained PATCH/DELETE too. See
				// handlers_admin_clients.go / handlers_admin_resource_servers.go.
				pr.Get("/clients", s.handleAdminClientsList)
				pr.Post("/clients", s.handleAdminClientCreate)
				// FE-4d: declared before /{clientId} for readability — chi
				// already ranks this static segment over the dynamic one
				// regardless of order (same note as clients/credentials
				// above).
				pr.Get("/clients/count", s.handleAdminClientCount)
				pr.Get("/clients/{clientId}", s.handleAdminClientGet)
				pr.Patch("/clients/{clientId}", s.handleAdminClientUpdate)
				pr.Delete("/clients/{clientId}", s.handleAdminClientDelete)
				pr.Post("/clients/{clientId}/client-secret", s.handleAdminClientSecretRegenerate)

				pr.Get("/resource-servers", s.handleAdminResourceServersList)
				pr.Post("/resource-servers", s.handleAdminResourceServerCreate)
				pr.Get("/resource-servers/{rsId}", s.handleAdminResourceServerGet)
				pr.Patch("/resource-servers/{rsId}", s.handleAdminResourceServerUpdate)
				pr.Delete("/resource-servers/{rsId}", s.handleAdminResourceServerDelete)
				pr.Get("/resource-servers/{rsId}/scopes", s.handleAdminScopesList)
				pr.Post("/resource-servers/{rsId}/scopes", s.handleAdminScopeAdd)
				pr.Patch("/resource-servers/{rsId}/scopes/{scopeId}", s.handleAdminScopeUpdate)
				pr.Delete("/resource-servers/{rsId}/scopes/{scopeId}", s.handleAdminScopeDelete)

				// Stage UI-3d: tenant-role CRUD (list + assign/revoke/held-names
				// already existed, UI-3b) and the whole application-role tier — RS-
				// scoped CRUD, scope bundling, and assign/revoke/held-names for a
				// user. See handlers_admin_tenant_roles.go /
				// handlers_admin_application_roles.go.
				pr.Post("/roles", s.handleAdminRoleCreate)
				pr.Get("/roles/{roleId}", s.handleAdminRoleGet)
				pr.Patch("/roles/{roleId}", s.handleAdminRoleUpdate)
				pr.Delete("/roles/{roleId}", s.handleAdminRoleDelete)
				// FE-4b: role detail's assignees list (spec §6.4.5).
				pr.Get("/roles/{roleId}/assignees", s.handleAdminRoleAssignees)

				pr.Get("/resource-servers/{rsId}/roles", s.handleAdminApplicationRolesList)
				pr.Post("/resource-servers/{rsId}/roles", s.handleAdminApplicationRoleCreate)
				pr.Get("/resource-servers/{rsId}/roles/{roleId}", s.handleAdminApplicationRoleGet)
				pr.Patch("/resource-servers/{rsId}/roles/{roleId}", s.handleAdminApplicationRoleUpdate)
				pr.Delete("/resource-servers/{rsId}/roles/{roleId}", s.handleAdminApplicationRoleDelete)
				// FE-4b: application-role detail's assignees list (spec §6.4.5).
				pr.Get("/resource-servers/{rsId}/roles/{roleId}/assignees", s.handleAdminApplicationRoleAssignees)
				pr.Get("/resource-servers/{rsId}/roles/{roleId}/scopes", s.handleAdminApplicationRoleScopesList)
				pr.Post("/resource-servers/{rsId}/roles/{roleId}/scopes/{scopeId}", s.handleAdminApplicationRoleScopeBundle(http.MethodPost))
				pr.Delete("/resource-servers/{rsId}/roles/{roleId}/scopes/{scopeId}", s.handleAdminApplicationRoleScopeBundle(http.MethodDelete))

				pr.Get("/users/{userId}/application-roles", s.handleAdminUserApplicationRoles)
				pr.Post("/users/{userId}/application-roles/{roleId}", s.handleAdminUserApplicationRoleAssignment(http.MethodPost))
				pr.Delete("/users/{userId}/application-roles/{roleId}", s.handleAdminUserApplicationRoleAssignment(http.MethodDelete))

				// Stage UI-3e: tenant settings (branding + registration mode —
				// both GET/PUT, full-replacement semantics on the backend, see
				// handlers_admin_tenant_settings.go) and the read-only audit
				// query (handlers_admin_audit.go).
				pr.Get("/branding", s.handleAdminBrandingGet)
				pr.Put("/branding", s.handleAdminBrandingUpdate)
				pr.Get("/registration-config", s.handleAdminRegistrationConfigGet)
				pr.Put("/registration-config", s.handleAdminRegistrationConfigUpdate)
				pr.Get("/audit-events", s.handleAdminAuditEventsList)
			})
		})
	})

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 3 — the platform-admin console (Stage UI-4). See this file's
	// header comment above for why the route shape has no slug segment.
	// ──────────────────────────────────────────────────────────────────────────
	r.Route("/platform", func(plr chi.Router) {
		plr.Use(middleware.NoCacheMiddleware)

		plr.Route("/api", func(par chi.Router) {
			par.Use(middleware.CSRFTokenMiddleware(bffCfg.IsProduction))
			par.Use(middleware.CSRFValidationMiddleware)

			// Always-200 probe (mirrors GET /t/{slug}/api/session) and the
			// login/signout pair — all three called by the SPA via fetch(),
			// never a browser navigation (see this file's header comment on
			// why platform-admin login has no OAuth2 round trip to redirect
			// through).
			par.Get("/session", s.handlePlatformSession)
			par.Post("/login", s.handlePlatformLogin)
			par.Post("/signout", s.handlePlatformSignOut)

			par.Group(func(gr chi.Router) {
				gr.Use(middleware.RequirePlatformSession())

				gr.Get("/tenants", s.handlePlatformTenantsList)
				gr.Post("/tenants", s.handlePlatformTenantProvision)
				gr.Get("/tenants/{tenantId}", s.handlePlatformTenantGet)
				gr.Post("/tenants/{tenantId}/activate", s.handlePlatformTenantLifecycle("activate"))
				gr.Post("/tenants/{tenantId}/suspend", s.handlePlatformTenantLifecycle("suspend"))
				gr.Delete("/tenants/{tenantId}", s.handlePlatformTenantDelete)

				// Stage UI-4b: bootstrap a tenant's first admin, list its
				// users (so the console can find a userId to reissue for),
				// and reissue an unused onboarding credential. See
				// handlers_platform_onboarding.go's header comment.
				gr.Get("/tenants/{tenantId}/users", s.handlePlatformTenantUsersList)
				gr.Post("/tenants/{tenantId}/bootstrap-admin", s.handlePlatformTenantBootstrapAdmin)
				gr.Post("/tenants/{tenantId}/users/{userId}/reissue-onboarding-credential", s.handlePlatformTenantReissueCredential)

				gr.Get("/admins", s.handlePlatformAdminsList)
				gr.Post("/admins", s.handlePlatformAdminCreate)
				gr.Post("/admins/{adminId}/suspend", s.handlePlatformAdminLifecycle("suspend"))
				gr.Post("/admins/{adminId}/activate", s.handlePlatformAdminLifecycle("activate"))
				gr.Get("/admins/{adminId}/roles", s.handlePlatformAdminRoles)
				gr.Post("/admins/{adminId}/roles/{roleName}", s.handlePlatformAdminRoleAssignment(http.MethodPost))
				gr.Delete("/admins/{adminId}/roles/{roleName}", s.handlePlatformAdminRoleAssignment(http.MethodDelete))
			})
		})
	})

	// The admin-console callback path is FIXED, not slug-aware, by
	// construction — Option A registers exactly one redirect_uri per tenant
	// client, so every tenant's admin-console client shares this single
	// callback. The slug is recovered from the state param / oauth_ctx_{slug}
	// cookie inside the handler, not from the route.
	r.Get(bffCfg.AdminCallbackPath, s.handleAdminCallback)

	// ──────────────────────────────────────────────────────────────────────────
	// SPA catch-all — serve embedded Vue dist/ (fallback to index.html).
	// chi propagates a NotFound handler registered here into subrouters
	// declared before it (r.Route("/t/{slug}", ...) above), which is what
	// lets /t/{slug}/console fall through to the SPA while
	// internal/static/embed.go's tenantAPIPath check still 404s a bad
	// /t/{slug}/api/... path instead of silently serving index.html.
	// ──────────────────────────────────────────────────────────────────────────
	r.NotFound(static.SPAHandler().ServeHTTP)

	return r
}

// handleHealthCheck reports basic liveness. It no longer reports database
// health — the BFF has no direct database connection (per the "no direct
// database access, ever" architectural decision).
func (s *Server) handleHealthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]any{
		"status":    "ok",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
}
