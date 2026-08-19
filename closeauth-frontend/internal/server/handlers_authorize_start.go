package server

import (
	"encoding/json"
	"net/http"
	"regexp"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/middleware"
)

// tenantIDPattern mirrors spec §6.1's own field-validation regex exactly —
// the SAME shape EntryController (Java) and the SPA's entry-field validation
// both enforce. Deliberately NOT validSlug (handlers_admin_auth.go): that
// pattern forbids underscores, but a real Tenant ID is `ten_`-prefixed, and
// once BE-A ships, Tenant.slug itself carries that prefix — reusing
// validSlug here would reject every legitimate tenantId this endpoint is
// meant to accept.
var tenantIDPattern = regexp.MustCompile(`^ten_[a-z0-9][a-z0-9-]{1,45}$`)

func validTenantID(id string) bool {
	return tenantIDPattern.MatchString(id)
}

// startAuthorizeFlow is the shared core of handleAdminAuthStart (GET
// /t/{slug}/admin/login, a real browser navigation) and handleAuthorizeStart
// below (POST /api/auth/authorize/start, a JSON fetch()): PKCE + state +
// the OAuthContext cookie + the /oauth2/authorize URL, for the SAME
// admin-console-{slug} client either way. The OAuthContext cookie
// SaveOAuthContext sets is Path=/ (internal/middleware/oauth_context.go),
// so it's visible to /admin/callback regardless of which of these two
// callers set it — the whole token-exchange/session-creation callback needs
// ZERO changes to serve both entry points.
func (s *Server) startAuthorizeFlow(w http.ResponseWriter, slug, returnTo string, attempt int) (string, error) {
	bffCfg := s.bffConfig()
	clientID := bffCfg.AdminClientID(slug)

	pkce, err := backend.NewPKCE()
	if err != nil {
		return "", err
	}
	state, err := middleware.NewState(slug)
	if err != nil {
		return "", err
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
		return "", err
	}

	return s.oauthClient.AuthorizeURL(clientID, bffCfg.AdminScope, pkce, state), nil
}

// FE-2a (spec §6.2.1 step 3): POST /api/auth/authorize/start — the JSON
// counterpart to handleAdminAuthStart, for the unauthenticated tenant
// resolver (TenantResolverView.vue). Unlike that GET handler, this route has
// no {slug} path segment to read — the resolver calls this via fetch() with
// the already-validated tenantId in the JSON body — so it returns the
// authorize URL as JSON for the SPA to window.location.assign ITSELF. This
// handler must NEVER fetch that URL server-side, and the SPA must never
// fetch() it either: see AuthorizeURL's own doc comment on why only a
// genuine top-level browser navigation carries the right Accept header and
// the SameSite=Lax backend session cookie.
//
// Deliberately thinner than handleAdminAuthStart: no returnTo (always the
// tenant's console — the same default safeReturnTo produces for an empty
// returnTo), no denial-marker loop break (that marker's cookie is
// Path=/t/{slug}, invisible to this /api/... route), no session-probe skip
// for the pre-flight (there's no admin-session cookie reachable from here
// either, so every call pays for preflightTenant) — this route is
// rate-limited (routes.go) for exactly that per-call cost.
func (s *Server) handleAuthorizeStart(w http.ResponseWriter, r *http.Request) {
	var body struct {
		TenantID string `json:"tenantId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "Malformed request body.")
		return
	}
	slug := body.TenantID
	if !validTenantID(slug) {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "Malformed tenantId.")
		return
	}

	if s.oauthClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin console OAuth client is not configured.")
		return
	}

	// Same loop-break-B cap as handleAdminAuthStart — a scripted caller
	// hammering this endpoint for one tenant shouldn't mint unbounded
	// OAuthContext cookies either.
	attempt := 1
	if existing, err := middleware.GetOAuthContext(r, slug); err == nil {
		attempt = existing.Attempt + 1
	}
	if attempt > maxOAuthAttempts {
		writeJSONError(w, http.StatusTooManyRequests, "login_loop", "Too many attempts. Try again later.")
		return
	}

	clientID := s.bffConfig().AdminClientID(slug)
	if !s.preflightTenant(r.Context(), clientID) {
		writeJSONError(w, http.StatusNotFound, "unknown_tenant", "No such workspace.")
		return
	}

	authorizeURL, err := s.startAuthorizeFlow(w, slug, "", attempt)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to start the login flow.")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"authorizeUrl": authorizeURL})
}
