package server

import (
	"encoding/json"
	"io"
	"net/http"
	"time"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/middleware"
)

// Stage UI-4: the platform-admin console's login/session/signout endpoints —
// POST /platform/api/login, GET /platform/api/session, POST
// /platform/api/signout. Genuinely NOT the tenant-admin pattern
// (handlers_admin_auth.go): platform-admin login is a plain JSON POST
// against the backend's one unauthenticated /v1 endpoint
// (POST /v1/platform/auth/token) — no OAuth2 authorize dance, no PKCE, no
// browser-navigation redirect, no cross-origin-cookie concerns, so this is
// called by the SPA via fetch() like any other JSON endpoint, unlike
// handlers_admin_auth.go's real top-level navigations.

// platformLoginRequest is POST /platform/api/login's body.
type platformLoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

const maxPlatformLoginBodyBytes = 1 << 12 // 4 KiB — an email+password pair, generously bounded

// handlePlatformLogin authenticates against the backend's platform-admin
// token mint and, on success, establishes a session — refusing HERE, at the
// boundary, if the token's own `roles` claim doesn't include PLATFORM_ADMIN
// (the default for a freshly created admin; PlatformAdminService.
// createPlatformAdmin grants none), rather than silently handing back a
// session every subsequent /platform/api/** call would 403 anyway.
//
// Deliberately calls s.adminClient.PostJSON directly rather than
// AdminClient.MintPlatformAdminToken: that helper collapses any non-2xx
// response into a bare Go error, discarding the backend's enumeration-safe
// RFC 7807 401 "invalid_credentials" body this handler needs to forward
// verbatim (never "no such admin" / "wrong password" — same uniform-failure
// discipline PlatformAdminService.authenticate enforces server-side).
//
// Roles come from decoding the minted JWT's own `roles` claim locally
// (backend.DecodeJWTClaims — same trusted-provenance pattern
// handleAdminCallback uses for tenant_roles), NOT from calling
// GET /v1/platform/me: that endpoint is ITSELF gated by
// @RequiresPlatformAdmin (PlatformMeController), so a genuinely zero-role
// admin — exactly the case this handler most needs to detect — can never
// successfully call it. Relying on it here would turn "you have no roles"
// into an indistinguishable "the backend is unreachable" 502, which is
// wrong twice over. Email has no such local source (it isn't a JWT claim on
// this backend), so it's backfilled via PlatformMe ONLY after roles already
// confirm PLATFORM_ADMIN — the one case where that call can actually
// succeed — and its failure is non-fatal (the session is still valid
// without it, matching handleAdminCallback's identical tolerance for its
// own GET /v1/me email backfill).
func (s *Server) handlePlatformLogin(w http.ResponseWriter, r *http.Request) {
	if s.adminClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin API client is not configured.")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxPlatformLoginBodyBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	var req platformLoginRequest
	if err := json.Unmarshal(raw, &req); err != nil || req.Email == "" || req.Password == "" {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "email and password are required.")
		return
	}

	resp, err := s.adminClient.PostJSON(r.Context(), "", "/v1/platform/auth/token", map[string]string{
		"email":    req.Email,
		"password": req.Password,
	})
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	if !resp.OK() {
		// Uniform, enumeration-safe: forward the backend's own 401
		// invalid_credentials as-is rather than reshaping it.
		problem, perr := resp.Problem()
		if perr != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Unexpected response from the backend.")
			return
		}
		writeProblemError(w, resp.StatusCode, problem)
		return
	}

	var token struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := resp.JSON(&token); err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Unexpected response from the backend.")
		return
	}

	claims, err := backend.DecodeJWTClaims(token.AccessToken)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Unexpected response from the backend.")
		return
	}

	session := &middleware.PlatformSession{
		AdminID:        claims.String("sub"),
		Roles:          claims.StringSlice("roles"),
		AccessToken:    token.AccessToken,
		AccessTokenExp: time.Now().Add(time.Duration(token.ExpiresIn) * time.Second).Unix(),
	}

	// Creation alone doesn't grant access — a zero-role admin authenticates
	// fine (real credentials) but gets refused HERE, no session cookie set,
	// rather than a session every later call would 403 out from under.
	if !session.IsPlatformAdmin() {
		writeJSONError(w, http.StatusForbidden, "not_platform_admin",
			"This account does not hold PLATFORM_ADMIN and cannot use this console.")
		return
	}

	// Email is NOT a JWT claim on this backend (PlatformAdminTokenService's
	// claim set) — backfill via GET /v1/platform/me, which can only succeed
	// now that roles are confirmed to include PLATFORM_ADMIN (that endpoint
	// is itself @RequiresPlatformAdmin-gated). Non-fatal on failure: the
	// session is still valid without an email, the SPA just won't have one
	// to display — same tolerance handleAdminCallback shows its own email
	// backfill.
	if me, err := s.adminClient.PlatformMe(r.Context(), token.AccessToken); err == nil {
		session.Email = me.Email
	}

	if err := middleware.SetPlatformSession(w, session, s.bffConfig().IsProduction); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "Failed to establish the platform session.")
		return
	}
	writeJSONOK(w, platformSessionResponse{
		Authenticated:        true,
		AdminID:              session.AdminID,
		Email:                session.Email,
		Roles:                session.Roles,
		AccessTokenExpiresAt: time.Unix(session.AccessTokenExp, 0).UTC().Format(time.RFC3339),
	})
}

// platformSessionResponse is GET /platform/api/session's (and a successful
// login's) body shape. Deliberately never includes AccessToken — the access
// token never reaches browser JS, full stop.
type platformSessionResponse struct {
	Authenticated        bool     `json:"authenticated"`
	AdminID              string   `json:"adminId,omitempty"`
	Email                string   `json:"email,omitempty"`
	Roles                []string `json:"roles,omitempty"`
	AccessTokenExpiresAt string   `json:"accessTokenExpiresAt,omitempty"`
}

// handlePlatformSession is a state PROBE, not a protected resource — always
// 200, mirroring handleAdminSession's reasoning: the SPA's router guard
// checks this on every guarded navigation, and "not signed in" is a normal
// state, not a failure.
func (s *Server) handlePlatformSession(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetPlatformSession(r)
	if err != nil {
		writeJSONOK(w, platformSessionResponse{Authenticated: false})
		return
	}
	if session.Expired(time.Now()) {
		writeJSONOK(w, platformSessionResponse{Authenticated: false})
		return
	}
	writeJSONOK(w, platformSessionResponse{
		Authenticated:        true,
		AdminID:              session.AdminID,
		Email:                session.Email,
		Roles:                session.Roles,
		AccessTokenExpiresAt: time.Unix(session.AccessTokenExp, 0).UTC().Format(time.RFC3339),
	})
}

// handlePlatformSignOut clears the BFF's own platform session cookie. There
// is no backend logout cascade to call for a platform-admin token (it has no
// refresh token and no SSO session to end) — clearing the cookie is the
// entire operation.
func (s *Server) handlePlatformSignOut(w http.ResponseWriter, r *http.Request) {
	middleware.ClearPlatformSession(w, s.bffConfig().IsProduction)
	w.WriteHeader(http.StatusNoContent)
}
