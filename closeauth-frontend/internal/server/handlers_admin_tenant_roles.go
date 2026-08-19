package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3d: tenant-role CRUD (create/get/update/delete). GET /roles (the
// list) and the assign/revoke/held-names trio already exist in
// handlers_admin_users.go (UI-3b) — this file only adds what that stage
// deliberately left out: the backend has always had full CRUD on
// TenantRoleController, this console just hadn't wrapped it yet. Same
// thin-relay shape as every other admin-CRUD handler: chi.URLParam ->
// adminSessionOrError -> validUUID on every id -> (readJSONBody for a body)
// -> s.adminClient.* -> s.writeAdminAPIResult.
//
// System-role immutability (role.system_immutable, 403) needs no handling
// here — admin_api_result.go's 403 split already forwards a domain code
// other than access_denied as an ordinary error without touching the
// session; this file relies on that unchanged.

func (s *Server) handleAdminRoleCreate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/roles", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminRoleGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_role_id", "Malformed role id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/roles/"+roleID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminRoleUpdate(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_role_id", "Malformed role id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PatchJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/roles/"+roleID, body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminRoleAssignees backs GET /roles/{roleId}/assignees (FE-4b, spec
// §6.4.5's "role detail shows assignees") — a new backend read
// (TenantRoleController.assignees), same thin-relay shape as every other
// handler in this file.
func (s *Server) handleAdminRoleAssignees(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_role_id", "Malformed role id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/roles/"+roleID+"/assignees")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminRoleDelete(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_role_id", "Malformed role id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/roles/"+roleID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
