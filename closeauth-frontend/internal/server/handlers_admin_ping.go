package server

import (
	"net/http"

	"closeauth-frontend/internal/middleware"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3a: GET /t/{slug}/api/ping — the loop's actual proof that a
// backend @RequiresTenantAccess-gated endpoint accepts this session's
// access token. Deliberately the ONLY data endpoint this stage wires: it
// calls the backend's existing GET /v1/tenants/{tenantId}/admin-ping (the 7a
// demonstration endpoint for the cross-tenant admin guard — no CRUD, no new
// backend surface needed). Mounted behind middleware.RequireAdminSession, so
// by the time this handler runs the session is already known to be a
// TENANT_ADMIN with a non-near-expiry token; this handler's own 401/403
// mapping below handles the backend rejecting the token ANYWAY (revoked or
// role-changed mid-session — authorization is re-checked continuously, not
// just at login).
func (s *Server) handleAdminPing(w http.ResponseWriter, r *http.Request) {
	session, ok := middleware.AdminSessionFrom(r.Context())
	if !ok {
		// Unreachable in practice — RequireAdminSession always populates this
		// before calling next — but fail closed rather than panic if it ever
		// isn't (e.g. a future refactor drops the middleware from the route).
		writeJSONError(w, http.StatusUnauthorized, "unauthenticated", "No active tenant-admin session for this tenant.")
		return
	}
	if s.adminClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin API client is not configured.")
		return
	}

	slug := chi.URLParam(r, "slug")
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/admin-ping")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}

	// UI-3b: response mapping (401 -> reauth, 403 -> session clear +
	// denial, else forward) now lives in admin_api_result.go, shared with
	// every admin-CRUD handler. Behavior for this endpoint is unchanged.
	s.writeAdminAPIResult(w, slug, session, resp)
}
