package server

import (
	"net/http"

	"closeauth-frontend/internal/backend"

	"github.com/go-chi/chi/v5"
)

// FE-4d: the tenant-console's self-service surface — GET /t/{slug}/api/me,
// GET/.../me/sessions, DELETE .../me/sessions/{sessionId}, POST
// .../me/change-password. Reachable by EVERY tenant user with a live
// session, admin or not (RequireTenantSession, not RequireAdminSession —
// see routes.go's own comment on the new mr.Group this file's handlers
// mount into). Thin proxies to the backend's /v1/me/** (bearer-authenticated,
// self-scoped via the token's own sub claim — no {tenantId} in the path,
// unlike every admin-CRUD handler in handlers_admin_users.go and siblings).
//
// Response mapping goes through writeMeAPIResult, NOT writeAdminAPIResult
// (admin_api_result.go) — that helper's 403 branch clears the session and
// sets a "not_tenant_admin" denial marker, which is the correct behavior
// for an admin-CRUD call (an admin's role was revoked mid-session) but
// would be a real bug here: a self-service 403 (e.g. user.invalid_credentials
// on a wrong current password) has nothing to do with tenant-admin status
// and must never clear a perfectly valid non-admin session.
func (s *Server) handleMeGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/me")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	writeMeAPIResult(w, slug, resp)
}

func (s *Server) handleMeSessionsList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/me/sessions")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	writeMeAPIResult(w, slug, resp)
}

func (s *Server) handleMeSessionRevoke(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	sessionID := chi.URLParam(r, "sessionId")
	if !validUUID(sessionID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_session_id", "sessionId must be a valid UUID.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, "/v1/me/sessions/"+sessionID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	writeMeAPIResult(w, slug, resp)
}

func (s *Server) handleMeChangePassword(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/me/change-password", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	writeMeAPIResult(w, slug, resp)
}

// writeMeAPIResult is writeAdminAPIResult's self-service counterpart — same
// pass-through shape for 2xx/400/404/409, same reauth-on-401 behavior, but
// NO admin-session-clearing branch on 403 (see this file's header comment
// for why that would be a bug here).
func writeMeAPIResult(w http.ResponseWriter, slug string, resp backend.APIResponse) {
	switch {
	case resp.OK():
		writeAdminAPIBody(w, resp)

	case resp.StatusCode == http.StatusUnauthorized:
		writeReauthRequired(w, slug)

	case resp.StatusCode == http.StatusBadRequest,
		resp.StatusCode == http.StatusForbidden,
		resp.StatusCode == http.StatusNotFound,
		resp.StatusCode == http.StatusConflict:
		problem, err := resp.Problem()
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Unexpected response from the backend.")
			return
		}
		writeProblemError(w, resp.StatusCode, problem)

	default:
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Unexpected response from the backend.")
	}
}
