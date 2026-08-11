package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-4b: the platform console's tenant-onboarding surface — listing a
// tenant's users, bootstrapping its first TENANT_ADMIN, and reissuing an
// unused onboarding credential, against PlatformTenantOnboardingController
// and TenantUserController. Same shape as handlers_platform_tenants.go:
// platformSessionOrError preamble, validUUID on every path segment,
// s.adminClient carries the session's bearer token, s.writePlatformAPIResult
// maps the response.
//
// handlePlatformTenantUsersList is the one route in this file that reaches
// /v1/tenants/{tenantId}/users rather than /v1/platform/tenants/** —
// deliberately: reissue-onboarding-credential is keyed by {tenantId, userId},
// and the tenant list carries no user data, so the console needs a way to
// find that id. TenantUserController is @RequiresTenantAccess, which grants
// a platform-admin token access to ANY tenant (AdminAuthorization.
// hasTenantAccess) — so this is a legitimate cross-namespace read, not a
// gate bypass.
func (s *Server) handlePlatformTenantUsersList(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	tenantID := chi.URLParam(r, "tenantId")
	if !validUUID(tenantID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_tenant_id", "Malformed tenant id.")
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken, "/v1/tenants/"+tenantID+"/users", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

// handlePlatformTenantBootstrapAdmin backs the atomic "create this tenant's
// first admin" composite. The backend requires the tenant to be ACTIVE
// (TenantService.requireActiveTenant) — a PROVISIONING or SUSPENDED tenant
// comes back as a 403 tenant.not_active, a domain error, not an
// authorization denial. See writePlatformAPIResult's 403 branch: it must NOT
// treat this as "no longer PLATFORM_ADMIN".
func (s *Server) handlePlatformTenantBootstrapAdmin(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	tenantID := chi.URLParam(r, "tenantId")
	if !validUUID(tenantID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_tenant_id", "Malformed tenant id.")
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_json", "Request body must be valid JSON.")
		return
	}
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/platform/tenants/"+tenantID+"/bootstrap-admin", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}

// handlePlatformTenantReissueCredential backs regenerating a fresh temp
// credential for an existing, un-rotated admin. No request body — the
// backend re-sends to the user's existing email; it cannot correct a typo'd
// address (PlatformTenantOnboardingController.reissueOnboardingCredential
// takes only the two path variables).
func (s *Server) handlePlatformTenantReissueCredential(w http.ResponseWriter, r *http.Request) {
	session, ok := s.platformSessionOrError(w, r)
	if !ok {
		return
	}
	tenantID := chi.URLParam(r, "tenantId")
	userID := chi.URLParam(r, "userId")
	if !validUUID(tenantID) || !validUUID(userID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_id", "Malformed tenant or user id.")
		return
	}
	resp, err := s.adminClient.Post(r.Context(), session.AccessToken,
		"/v1/platform/tenants/"+tenantID+"/users/"+userID+"/reissue-onboarding-credential")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writePlatformAPIResult(w, resp)
}
