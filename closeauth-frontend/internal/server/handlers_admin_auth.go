package server

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/middleware"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3a: the tenant-admin console's login/reauth initiation
// (GET /t/{slug}/admin/login and /admin/reauth, same handler) and its
// callback (GET /admin/callback, fixed by construction — see routes.go).
//
// Both /admin/login and /admin/reauth are real top-level browser
// navigations, NEVER something the SPA calls via fetch(): the backend's
// unauthenticated-entry-point redirect on /oauth2/authorize is registered
// only for Accept: text/html (a JSON Accept 401s instead of reaching
// /login), and its CLOSEAUTH_SESSION cookie is SameSite=Lax, which a
// same-process HTTP call never carries. src/router/index.ts's guard performs
// window.location.assign(...) to these paths, not an XHR.
//
// This file also implements the three named loop-prevention mechanisms (see
// the stage plan): a denial marker short-circuiting a non-admin's repeat
// visit straight to /denied (loop break A, below); an Attempt counter
// capping pathological repeated round trips with no successful callback
// (loop break B); and a side-effect-free tenant pre-flight converting an
// unprovisioned tenant's dead end into a clear auth-error (loop break C).
// The ordinary 12h-absolute-session-timeout case needs none of this — it
// self-heals through the existing UI-2 login machinery (see the stage plan's
// "Loop prevention" section).

var slugPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)

// uuidPattern matches a canonical (hyphenated, case-insensitive) UUID —
// deliberately not validating the version/variant nibbles, since the backend
// itself is the authority on whether an id is well-formed and exists; this
// is purely a shape check.
var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// validSlug guards every place a slug is interpolated into a cookie name,
// cookie path, or redirect URL — an injection guard, not cosmetics.
func validSlug(slug string) bool {
	return slugPattern.MatchString(slug)
}

// validUUID guards every place a path-supplied {userId}/{roleId} is
// interpolated into a backend admin-API URL (handlers_admin_users.go) — a
// malformed id is rejected locally with 400 rather than forwarded, so the
// backend's URL space is never probed with attacker-controlled path
// segments.
func validUUID(id string) bool {
	return uuidPattern.MatchString(id)
}

// safeReturnTo is the open-redirect guard on the returnTo query parameter:
// it must be a same-origin path under /t/{slug}/, or the tenant console's
// default landing path is used instead.
func safeReturnTo(slug, raw string) string {
	def := "/t/" + slug + "/console"
	if raw == "" {
		return def
	}
	u, err := url.Parse(raw)
	if err != nil || u.IsAbs() || u.Host != "" {
		return def
	}
	if !strings.HasPrefix(u.Path, "/t/"+slug+"/") {
		return def
	}
	return raw
}

// preflightTenant checks, side-effect-free, whether clientID resolves on the
// backend via the same GET /login?client_id= the hosted login page itself
// uses (LoginController.loginContext: always 200, but the tenantId field is
// present ONLY when the client_id resolves — API_REFERENCE.md §1). Only
// tenants provisioned after Option A landed have an admin-console-{slug}
// client; without this check, an older tenant's admin would land on the
// backend's raw invalid_client page instead of a clear in-app message.
//
// Fails OPEN (returns true) on any transport/decode error or when
// s.adminClient is nil — this is a niceness for a clearer error message, not
// a security control (the real security is the callback's verification
// triple), so a check that can't run must never itself block login.
func (s *Server) preflightTenant(ctx context.Context, clientID string) bool {
	if s.adminClient == nil {
		return true
	}
	resp, err := s.adminClient.Get(ctx, "", "/login?client_id="+url.QueryEscape(clientID))
	if err != nil || !resp.OK() {
		return true
	}
	var body struct {
		TenantID string `json:"tenantId"`
	}
	if err := resp.JSON(&body); err != nil {
		return true
	}
	return body.TenantID != ""
}

// maxOAuthAttempts caps loop break B: a login/reauth round trip reached
// repeatedly with no successful callback in between (the oauth_ctx_{slug}
// cookie's Attempt field is cleared on every successful callback, so a
// healthy flow never approaches this).
const maxOAuthAttempts = 3

func (s *Server) handleAdminAuthStart(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	if !validSlug(slug) {
		http.Redirect(w, r, "/t/unknown/auth-error?reason=invalid_slug", http.StatusFound)
		return
	}
	bffCfg := s.bffConfig()
	returnTo := safeReturnTo(slug, r.URL.Query().Get("returnTo"))

	// Loop break A: an authenticated-but-non-admin user's repeat visit must
	// not silently re-run the whole round trip and land on /denied again —
	// short-circuit straight there without touching /oauth2/authorize.
	if marker, err := middleware.GetAdminDenied(r, slug); err == nil {
		http.Redirect(w, r, "/t/"+slug+"/denied?reason="+url.QueryEscape(marker.Reason), http.StatusFound)
		return
	}

	// Loop break B: cap pathological repeated attempts.
	attempt := 1
	if existing, err := middleware.GetOAuthContext(r, slug); err == nil {
		attempt = existing.Attempt + 1
	}
	if attempt > maxOAuthAttempts {
		http.Redirect(w, r, "/t/"+slug+"/auth-error?reason=login_loop", http.StatusFound)
		return
	}

	if s.oauthClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin console OAuth client is not configured.")
		return
	}

	clientID := bffCfg.AdminClientID(slug)

	// Loop break C: only pre-flight when there's no existing session for the
	// slug, so a healthy reauth never pays for it.
	if _, err := middleware.GetAdminSession(r, slug); err != nil {
		if !s.preflightTenant(r.Context(), clientID) {
			http.Redirect(w, r, "/t/"+slug+"/auth-error?reason=unknown_tenant", http.StatusFound)
			return
		}
	}

	pkce, err := backend.NewPKCE()
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to start the login flow.")
		return
	}
	state, err := middleware.NewState(slug)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to start the login flow.")
		return
	}

	oauthCtx := &middleware.OAuthContext{
		ClientID:     clientID,
		RedirectURI:  bffCfg.AdminCallbackURL(),
		Scope:        bffCfg.AdminScope,
		State:        state,
		CodeVerifier: pkce.Verifier,
		ReturnTo:     returnTo,
		Attempt:      attempt,
	}
	if err := middleware.SaveOAuthContext(w, slug, oauthCtx, bffCfg.IsProduction); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to start the login flow.")
		return
	}

	authorizeURL := s.oauthClient.AuthorizeURL(clientID, bffCfg.AdminScope, pkce, state)
	http.Redirect(w, r, authorizeURL, http.StatusFound)
}

func (s *Server) handleAdminCallback(w http.ResponseWriter, r *http.Request) {
	bffCfg := s.bffConfig()
	query := r.URL.Query()

	// A denial or cancellation at the backend (e.g. the user declined, or
	// the client/tenant is invalid) — never re-initiate; land on a dead-end
	// error page instead.
	if errParam := query.Get("error"); errParam != "" {
		slug := middleware.SlugFromState(query.Get("state"))
		if !validSlug(slug) {
			slug = "unknown"
		}
		http.Redirect(w, r, "/t/"+slug+"/auth-error?reason="+url.QueryEscape(errParam), http.StatusFound)
		return
	}

	state := query.Get("state")
	code := query.Get("code")
	slug := middleware.SlugFromState(state)
	if !validSlug(slug) || code == "" {
		http.Redirect(w, r, "/t/unknown/auth-error?reason=invalid_state", http.StatusFound)
		return
	}

	oauthCtx, err := middleware.GetOAuthContext(r, slug)
	if err != nil || !middleware.StateMatches(oauthCtx.State, state) {
		http.Redirect(w, r, "/t/"+slug+"/auth-error?reason=invalid_state", http.StatusFound)
		return
	}

	if s.oauthClient == nil || s.adminClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin console OAuth client is not configured.")
		return
	}

	tokens, err := s.oauthClient.Exchange(r.Context(), oauthCtx.ClientID, "", code, oauthCtx.CodeVerifier)
	if err != nil {
		middleware.ClearOAuthContext(w, slug, bffCfg.IsProduction)
		http.Redirect(w, r, "/t/"+slug+"/auth-error?reason=token_exchange_failed", http.StatusFound)
		return
	}

	claims, err := backend.DecodeJWTClaims(tokens.AccessToken)
	if err != nil {
		middleware.ClearOAuthContext(w, slug, bffCfg.IsProduction)
		http.Redirect(w, r, "/t/"+slug+"/auth-error?reason=token_exchange_failed", http.StatusFound)
		return
	}

	// The verification triple — authorization ≠ authentication. A valid
	// token from a successful login does NOT mean admin access: the token's
	// client_id must be exactly this slug's admin-console client (the
	// cryptographic slug binding — Option A registers one client per
	// tenant), tenant_id must be present, and tenant_roles must contain
	// TENANT_ADMIN. Any failure here means NO session is created.
	expectedClientID := bffCfg.AdminClientID(slug)
	tenantRoles := claims.StringSlice("tenant_roles")
	isTenantAdmin := false
	for _, role := range tenantRoles {
		if role == "TENANT_ADMIN" {
			isTenantAdmin = true
			break
		}
	}
	if claims.String("client_id") != expectedClientID || claims.String("tenant_id") == "" || !isTenantAdmin {
		middleware.ClearOAuthContext(w, slug, bffCfg.IsProduction)
		_ = middleware.SetAdminDenied(w, slug, "not_tenant_admin", claims.String("sub"), bffCfg.IsProduction)
		http.Redirect(w, r, "/t/"+slug+"/denied?reason=not_tenant_admin", http.StatusFound)
		return
	}

	// email is NOT a JWT claim on this backend — backfill via GET /v1/me.
	// A failure here doesn't block login (the session is still valid without
	// it); the SPA just won't have an email to display.
	email := ""
	if me, err := s.adminClient.Me(r.Context(), tokens.AccessToken); err == nil {
		email = me.Email
	}

	accessTokenExp, _ := claims.Int64("exp")

	session := &middleware.AdminSession{
		TenantID:       claims.String("tenant_id"),
		UserID:         claims.String("sub"),
		Email:          email,
		TenantRoles:    tenantRoles,
		ClientID:       expectedClientID,
		AccessToken:    tokens.AccessToken,
		AccessTokenExp: accessTokenExp,
	}
	if err := middleware.SetAdminSession(w, slug, session, bffCfg.IsProduction, bffCfg.SessionMaxAge); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to establish the admin session.")
		return
	}
	middleware.ClearOAuthContext(w, slug, bffCfg.IsProduction)
	middleware.ClearAdminDenied(w, slug, bffCfg.IsProduction) // a successful login clears any stale denial marker

	returnTo := oauthCtx.ReturnTo
	if returnTo == "" {
		returnTo = "/t/" + slug + "/console"
	}
	http.Redirect(w, r, returnTo, http.StatusFound)
}
