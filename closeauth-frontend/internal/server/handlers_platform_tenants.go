package server

import (
	"net/http"

	"closeauth-frontend/internal/middleware"

	"github.com/go-chi/chi/v5"
)

// Stage UI-4: the platform console's tenant-lifecycle surface —
// provision/list/get/activate/suspend/delete against /v1/platform/tenants/**
// (PlatformTenantController). Modelled directly on
// handlers_admin_users.go's shape: pull the session via
// middleware.PlatformSessionFrom (stashed by RequirePlatformSession, the
// only middleware mounted on this route group), call s.adminClient with the
// session's bearer token, map the result through s.writePlatformAPIResult.
// Unlike the tenant-admin console's handlers, there is no session tenant id
// to scope onto the URL — every call here IS cross-tenant, by construction.

// platformSessionOrError is handlers_platform_tenants.go's and
// handlers_platform_admins.go's shared preamble, mirroring
// adminSessionOrError's fail-closed-not-panic posture if a future refactor
// ever drops RequirePlatformSession from a route.
func (s *Server) platformSessionOrError(w http.ResponseWriter, r *http.Request) (*middleware.PlatformSession, bool) {
	session, ok := middleware.PlatformSessionFrom(r.Context())
	if !ok {
		writeJSONError(w, http.StatusUnauthorized, "unauthenticated", "No active platform-admin session.")
		return nil, false
	}
	if s.adminClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin API client is not configured.")
		return nil, false
	}
	return session, true
}

func (s *Server) handlePlatformTenantsList(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken, "/v1/platform/tenants", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

func (s *Server) handlePlatformTenantProvision(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/platform/tenants", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

func (s *Server) handlePlatformTenantGet(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	tenantID := chi.URLParam(r, "tenantId")
	if !validUUID(tenantID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_tenant_id", "Malformed tenant id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/platform/tenants/"+tenantID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

// handlePlatformTenantLifecycle backs activate/suspend — both a bare POST
// with no body, differing only in the backend action segment. Mirrors
// handleAdminUserLifecycle's shape.
func (s *Server) handlePlatformTenantLifecycle(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := s.platformSessionOrError(w, r)
		if !ok {
			return
		}
		tenantID := chi.URLParam(r, "tenantId")
		if !validUUID(tenantID) {
			writeJSONError(w, http.StatusBadRequest, "invalid_tenant_id", "Malformed tenant id.")
			return
		}
		resp, err := s.adminClient.Post(r.Context(), session.AccessToken, "/v1/platform/tenants/"+tenantID+"/"+action)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writePlatformAPIResult(w, resp)
	}
}

// handlePlatformTenantDelete backs the soft-delete (terminal — PROVISIONING
// | ACTIVE | SUSPENDED -> DELETED, nothing transitions out).
func (s *Server) handlePlatformTenantDelete(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	tenantID := chi.URLParam(r, "tenantId")
	if !validUUID(tenantID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_tenant_id", "Malformed tenant id.")
		return
	}
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, "/v1/platform/tenants/"+tenantID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}
