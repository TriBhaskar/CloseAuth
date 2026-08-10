package server

import (
	"encoding/json"
	"net/http"
	"time"

	"closeauth-frontend/internal/middleware"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3a: the tenant-admin console's state-probe and lifecycle
// endpoints — GET /t/{slug}/api/session, POST /t/{slug}/api/signout, and
// POST /t/{slug}/api/denied/dismiss. All three are called by the SPA via
// fetch() (unlike handlers_admin_auth.go's browser-navigation endpoints).

// sessionResponse is GET /t/{slug}/api/session's body shape. Deliberately
// never includes AccessToken or any other bearer credential — the access
// token never reaches browser JS, full stop. Every field beyond Slug is
// omitempty so an anonymous/denied caller gets a minimal body.
type sessionResponse struct {
	Slug                 string   `json:"slug"`
	Authenticated        bool     `json:"authenticated"`
	ReauthRequired       bool     `json:"reauthRequired,omitempty"`
	Denied               bool     `json:"denied,omitempty"`
	DeniedReason         string   `json:"deniedReason,omitempty"`
	TenantID             string   `json:"tenantId,omitempty"`
	UserID               string   `json:"userId,omitempty"`
	Email                string   `json:"email,omitempty"`
	TenantRoles          []string `json:"tenantRoles,omitempty"`
	AccessTokenExpiresAt string   `json:"accessTokenExpiresAt,omitempty"`
}

// handleAdminSession is a state PROBE, not a protected resource — it always
// returns 200. Making it ever error would conflate "not logged in" (a normal
// state the SPA's router guard checks on every guarded navigation) with a
// genuine failure. The SPA branches entirely on the body's fields.
func (s *Server) handleAdminSession(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	resp := sessionResponse{Slug: slug}

	if !validSlug(slug) {
		writeJSONOK(w, resp)
		return
	}

	session, err := middleware.GetAdminSession(r, slug)
	if err != nil {
		if marker, derr := middleware.GetAdminDenied(r, slug); derr == nil {
			resp.Denied = true
			resp.DeniedReason = marker.Reason
		}
		writeJSONOK(w, resp)
		return
	}

	resp.Authenticated = true
	resp.TenantID = session.TenantID
	resp.UserID = session.UserID
	resp.Email = session.Email
	resp.TenantRoles = session.TenantRoles
	resp.AccessTokenExpiresAt = time.Unix(session.AccessTokenExp, 0).UTC().Format(time.RFC3339)
	resp.ReauthRequired = session.NeedsReauth(time.Now(), s.bffConfig().ReauthSkew)
	writeJSONOK(w, resp)
}

// handleAdminSignOut clears the BFF's own cookies for slug only — it
// deliberately does NOT call the backend's POST /logout (the four-leg revoke
// cascade). Settled decision: "sign out" here ends the console session, not
// the tenant's whole SSO session, so signing back in afterward is silent
// (the backend's CLOSEAUTH_SESSION cookie is untouched).
func (s *Server) handleAdminSignOut(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	if !validSlug(slug) {
		writeJSONError(w, http.StatusBadRequest, "invalid_slug", "Unknown tenant.")
		return
	}
	isProd := s.bffConfig().IsProduction
	middleware.ClearAdminSession(w, slug, isProd)
	middleware.ClearOAuthContext(w, slug, isProd)
	middleware.ClearAdminDenied(w, slug, isProd)
	w.WriteHeader(http.StatusNoContent)
}

// handleAdminDeniedDismiss is the "try a different account" escape hatch for
// loop break A (see handlers_admin_auth.go): clearing the denial marker is
// the one explicit user action that lets a subsequent /admin/login reach
// /oauth2/authorize again.
func (s *Server) handleAdminDeniedDismiss(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	if !validSlug(slug) {
		writeJSONError(w, http.StatusBadRequest, "invalid_slug", "Unknown tenant.")
		return
	}
	middleware.ClearAdminDenied(w, slug, s.bffConfig().IsProduction)
	w.WriteHeader(http.StatusNoContent)
}

// writeReauthRequired emits the house error envelope plus the one extra
// field the SPA needs to perform the full-page navigation itself: the path
// to redirect to. The SPA appends its own ?returnTo=... — only the SPA knows
// where the user currently is.
func writeReauthRequired(w http.ResponseWriter, slug string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"error":             "reauth_required",
		"error_description": "The session's access token is expired or was rejected by the backend.",
		"reauthPath":        "/t/" + slug + "/admin/reauth",
	})
}

func writeJSONOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(v)
}
