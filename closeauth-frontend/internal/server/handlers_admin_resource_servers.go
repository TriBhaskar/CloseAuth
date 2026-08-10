package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3c: resource servers and their scope catalog — unlike clients,
// the backend exposes full CRUD here (TenantResourceServerController: list/
// create/get/update/delete + scopes list/add/update/delete), so this file is
// a plain thin relay throughout, same shape as handlers_admin_users.go.
// PATCH is used for both resource-server and scope updates — AdminClient's
// PatchJSON (internal/backend/admin_client.go) already existed and was
// unused before this stage; this is its first caller.
//
// Immutability is enforced structurally on the backend (audienceIdentifier
// and scopeName are simply absent from the update command DTOs, so a
// submitted value there is silently ignored rather than rejected) — nothing
// for the BFF to additionally validate; readJSONBody forwards whatever the
// SPA sent, byte-for-byte, same as every other admin-CRUD handler.

// ---- resource servers ---------------------------------------------------

func (s *Server) handleAdminResourceServersList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminResourceServerCreate(w http.ResponseWriter, r *http.Request) {
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
		"/v1/tenants/"+session.TenantID+"/resource-servers", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminResourceServerGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "Malformed resource server id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminResourceServerUpdate(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "Malformed resource server id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PatchJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID, body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminResourceServerDelete(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "Malformed resource server id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// ---- scope catalog --------------------------------------------------------

func (s *Server) handleAdminScopesList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "Malformed resource server id.")
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/scopes", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminScopeAdd(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "Malformed resource server id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/scopes", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminScopeUpdate(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	scopeID := chi.URLParam(r, "scopeId")
	if !validUUID(rsID) || !validUUID(scopeID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or scope id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PatchJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/scopes/"+scopeID, body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminScopeDelete(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	scopeID := chi.URLParam(r, "scopeId")
	if !validUUID(rsID) || !validUUID(scopeID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or scope id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/scopes/"+scopeID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
