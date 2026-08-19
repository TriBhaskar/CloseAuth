package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// FE-4a: the tenant-admin console's Sessions tab (spec §6.4.2 — device list,
// per-session revoke, "Revoke all sessions" behind a typed confirm in the
// SPA). Same shared preamble as handlers_admin_users.go: adminSessionOrError,
// validUUID on every path-supplied id, s.adminClient against
// /v1/tenants/{session.TenantID}/**, and s.writeAdminAPIResult for the
// response — the browser can never address another tenant's sessions even
// with a valid session, because TenantID always comes from the BFF's own
// session state, never a client-supplied value.

func (s *Server) handleAdminUserSessionsList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	userID := chi.URLParam(r, "userId")
	if !validUUID(userID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_user_id", "Malformed user id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/users/"+userID+"/sessions")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminUserSessionRevoke(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	userID := chi.URLParam(r, "userId")
	sessionID := chi.URLParam(r, "sessionId")
	if !validUUID(userID) || !validUUID(sessionID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed user or session id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/users/"+userID+"/sessions/"+sessionID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminUserSessionsRevokeAll backs "Revoke all sessions" — a single
// backend call (TenantUserController.revokeAllSessions), not a loop over
// individual revokes.
func (s *Server) handleAdminUserSessionsRevokeAll(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	userID := chi.URLParam(r, "userId")
	if !validUUID(userID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_user_id", "Malformed user id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/users/"+userID+"/sessions")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
