package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-4: the platform console's platform-admin management surface —
// list/create/suspend/activate/roles against /v1/platform/admins/**
// (PlatformAdminManagementController). Same shape as
// handlers_platform_tenants.go.

// validPlatformRole allow-lists {roleName} against the two platform roles
// V2__seed_platform_roles.sql seeds (PLATFORM_ADMIN, PLATFORM_SUPPORT) —
// that migration is the source of truth; there is no platform-role catalog
// endpoint to read this from dynamically, and none is planned (this stage's
// scope discipline). Unlike a UUID path segment, {roleName} is a free-form
// string, so this allow-list is what keeps it from being forwarded
// unvalidated into a backend admin-API URL — the same path-injection
// discipline validUUID applies to ids.
func validPlatformRole(roleName string) bool {
	return roleName == "PLATFORM_ADMIN" || roleName == "PLATFORM_SUPPORT"
}

func (s *Server) handlePlatformAdminsList(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken, "/v1/platform/admins", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

func (s *Server) handlePlatformAdminCreate(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/platform/admins", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

// handlePlatformAdminLifecycle backs suspend/activate — both a bare POST
// with no body, differing only in the backend action segment.
func (s *Server) handlePlatformAdminLifecycle(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := s.platformSessionOrError(w, r)
		if !ok {
			return
		}
		adminID := chi.URLParam(r, "adminId")
		if !validUUID(adminID) {
			writeJSONError(w, http.StatusBadRequest, "invalid_admin_id", "Malformed platform-admin id.")
			return
		}
		resp, err := s.adminClient.Post(r.Context(), session.AccessToken, "/v1/platform/admins/"+adminID+"/"+action)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writePlatformAPIResult(w, resp)
	}
}

// handlePlatformAdminRoles backs GET /admins/{adminId}/roles — the UI-4
// backend addition (PlatformAdminManagementController.roles) that makes the
// role display/assignment panel possible, the platform-tier analogue of
// handleAdminUserTenantRoles.
func (s *Server) handlePlatformAdminRoles(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	adminID := chi.URLParam(r, "adminId")
	if !validUUID(adminID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_admin_id", "Malformed platform-admin id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/platform/admins/"+adminID+"/roles")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

// handlePlatformAdminRoleAssignment backs POST/DELETE
// /admins/{adminId}/roles/{roleName} (assign/revoke), selected by method —
// mirrors handleAdminUserRoleAssignment's shape (handlers_admin_users.go).
func (s *Server) handlePlatformAdminRoleAssignment(method string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := s.platformSessionOrError(w, r)
		if !ok {
			return
		}
		adminID := chi.URLParam(r, "adminId")
		if !validUUID(adminID) {
			writeJSONError(w, http.StatusBadRequest, "invalid_admin_id", "Malformed platform-admin id.")
			return
		}
		roleName := chi.URLParam(r, "roleName")
		if !validPlatformRole(roleName) {
			writeJSONError(w, http.StatusBadRequest, "invalid_role_name", "Unknown platform role.")
			return
		}
		path := "/v1/platform/admins/" + adminID + "/roles/" + roleName

		if method == http.MethodPost {
			resp, err := s.adminClient.Post(r.Context(), session.AccessToken, path)
			if err != nil {
				writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
				return
			}
			s.writePlatformAPIResult(w, resp)
			return
		}

		resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, path)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writePlatformAPIResult(w, resp)
	}
}
