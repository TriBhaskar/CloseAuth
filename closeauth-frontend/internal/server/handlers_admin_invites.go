package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// FE-4a: the tenant-admin console's invitation create mode (spec §6.4.2).
// InviteController already existed backend-side (issue/list/revoke, INVITE_
// ONLY registration mode was settable via UI-3e's registration-config PUT),
// but — per routes.go's own header comment — "issuing/listing invites has no
// console route" until now. Same shared preamble as handlers_admin_users.go.

func (s *Server) handleAdminInvitesList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/invites")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminInviteCreate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/invites", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminInviteDelete(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	inviteID := chi.URLParam(r, "inviteId")
	if !validUUID(inviteID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_invite_id", "Malformed invite id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/invites/"+inviteID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
