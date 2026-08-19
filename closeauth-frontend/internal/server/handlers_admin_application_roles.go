package server

import (
	"net/http"
	"net/url"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3d: the application-role tier — RS-scoped role CRUD, scope-bundle
// management, and assign/revoke/held-names for a user, all wrapping
// ApplicationRoleController. Same thin-relay shape as
// handlers_admin_resource_servers.go: chi.URLParam -> adminSessionOrError ->
// validUUID on every id -> (readJSONBody for a body) -> s.adminClient.* ->
// s.writeAdminAPIResult.
//
// Unlike tenant roles, this tier has no reachable system/immutable concept
// (ApplicationRoleService.createApplicationRole hardcodes is_system=false) —
// nothing here needs to special-case a 403 the way the tenant tier's
// system-role rows do; the SPA's ApplicationRoleView.isSystem is always
// false in practice and no handler treats it specially.
//
// The scope-bundle add/remove endpoints deliberately do NOT map
// application_role.scope_rs_mismatch (400) to anything special: the SPA's
// scope picker only ever offers the role's own resource server's scopes
// (fed by GET /resource-servers/{rsId}/scopes, UI-3c), so the error is
// structurally unreachable from this console — readJSONBody/
// writeAdminAPIResult forward it unchanged like any other error, same as
// every other handler in this file.

// ---- application-role CRUD, RS-scoped -------------------------------------

func (s *Server) handleAdminApplicationRolesList(w http.ResponseWriter, r *http.Request) {
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
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminApplicationRoleCreate(w http.ResponseWriter, r *http.Request) {
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
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminApplicationRoleGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(rsID) || !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or role id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles/"+roleID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminApplicationRoleUpdate(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(rsID) || !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or role id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PatchJSON(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles/"+roleID, body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminApplicationRoleDelete(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(rsID) || !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or role id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles/"+roleID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminApplicationRoleAssignees backs GET
// /resource-servers/{rsId}/roles/{roleId}/assignees (FE-4b, spec §6.4.5's
// "role detail shows assignees") — a new backend read
// (ApplicationRoleController.assignees), same thin-relay shape as every
// other handler in this file.
func (s *Server) handleAdminApplicationRoleAssignees(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(rsID) || !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or role id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles/"+roleID+"/assignees")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// ---- scope bundle ----------------------------------------------------------

func (s *Server) handleAdminApplicationRoleScopesList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	rsID := chi.URLParam(r, "rsId")
	roleID := chi.URLParam(r, "roleId")
	if !validUUID(rsID) || !validUUID(roleID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server or role id.")
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/resource-servers/"+rsID+"/roles/"+roleID+"/scopes", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminApplicationRoleScopeBundle backs add (POST) and remove (DELETE)
// — identical shape, differing only in the AdminClient verb. Both are
// idempotent 204s on the backend.
func (s *Server) handleAdminApplicationRoleScopeBundle(method string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		slug := chi.URLParam(r, "slug")
		session, ok := s.adminSessionOrError(w, r)
		if !ok {
			return
		}
		rsID := chi.URLParam(r, "rsId")
		roleID := chi.URLParam(r, "roleId")
		scopeID := chi.URLParam(r, "scopeId")
		if !validUUID(rsID) || !validUUID(roleID) || !validUUID(scopeID) {
			writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed resource server, role, or scope id.")
			return
		}
		path := "/v1/tenants/" + session.TenantID + "/resource-servers/" + rsID + "/roles/" + roleID + "/scopes/" + scopeID

		if method == http.MethodPost {
			resp, err := s.adminClient.Post(r.Context(), session.AccessToken, path)
			if err != nil {
				writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
				return
			}
			s.writeAdminAPIResult(w, slug, session, resp)
			return
		}

		resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, path)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writeAdminAPIResult(w, slug, session, resp)
	}
}

// ---- user assignment --------------------------------------------------

// handleAdminUserApplicationRoles backs GET
// /users/{userId}/application-roles?resourceServerId= — the UI-3d backend
// addition (ApplicationRoleController.applicationRolesForUser). Deliberately
// RS-scoped, not tenant-wide: application-role names are unique only per
// resource server (uq_application_roles_rs_name), so a flat name list would
// be unjoinable against a per-RS catalog. resourceServerId is required here
// the same way it is on the backend — an omitted/malformed value 400s rather
// than silently forwarding an incomplete query.
func (s *Server) handleAdminUserApplicationRoles(w http.ResponseWriter, r *http.Request) {
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
	rsID := r.URL.Query().Get("resourceServerId")
	if !validUUID(rsID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_resource_server_id", "A valid resourceServerId query parameter is required.")
		return
	}
	query := url.Values{"resourceServerId": {rsID}}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/users/"+userID+"/application-roles", query)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminUserApplicationRoleAssignment backs assign (POST) and revoke
// (DELETE) — identical shape, differing only in the AdminClient verb. Unlike
// the tenant tier there is no last-admin guard here; both calls are
// idempotent 204s on the backend.
func (s *Server) handleAdminUserApplicationRoleAssignment(method string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		slug := chi.URLParam(r, "slug")
		session, ok := s.adminSessionOrError(w, r)
		if !ok {
			return
		}
		userID := chi.URLParam(r, "userId")
		roleID := chi.URLParam(r, "roleId")
		if !validUUID(userID) || !validUUID(roleID) {
			writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed user or role id.")
			return
		}
		path := "/v1/tenants/" + session.TenantID + "/users/" + userID + "/application-roles/" + roleID

		if method == http.MethodPost {
			resp, err := s.adminClient.Post(r.Context(), session.AccessToken, path)
			if err != nil {
				writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
				return
			}
			s.writeAdminAPIResult(w, slug, session, resp)
			return
		}

		resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, path)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writeAdminAPIResult(w, slug, session, resp)
	}
}
