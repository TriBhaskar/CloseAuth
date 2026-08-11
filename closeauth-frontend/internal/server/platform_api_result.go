package server

import (
	"net/http"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/middleware"
)

// Stage UI-4: the platform-admin console's response mapper — the
// cross-tenant analogue of admin_api_result.go's writeAdminAPIResult,
// reusing that file's writeAdminAPIBody/writeProblemError verbatim so RFC
// 7807 domain codes (e.g. "platform_admin.last_admin") and field-level
// validation errors survive to the browser exactly the same way.
//
// Two real differences from the tenant-tier mapper, both because there is no
// silent-renewal path on this surface (the platform-admin token is
// access-only, 5-minute TTL, no refresh — see platform_session.go):
//   - 401 clears the session and reports "session_expired", not
//     "reauth_required". This is also the suspension-kills-live-tokens path:
//     PlatformAdminRevocationTokenValidator rejects a suspended admin's
//     token mid-session (within seconds, per the sub-keyed revocation
//     marker), so a 401 here can mean "the token simply expired" OR "you
//     were just suspended" — either way, the only remedy is signing in
//     again, so one message covers both honestly.
//   - 403 clears the session ONLY when it's the @RequiresPlatformAdmin gate
//     itself denying (ApiExceptionHandler's generic "access_denied"). Stage
//     UI-4b's onboarding endpoints introduced this surface's first
//     business-rule 403 — bootstrap-admin/reissue-onboarding-credential
//     against a non-ACTIVE tenant come back as 403 tenant.not_active
//     (TenantService.requireActiveTenant, a FORBIDDEN-category domain
//     exception, not an authz denial). Treating every 403 as "no longer
//     PLATFORM_ADMIN" would silently sign the operator out mid-flow on a
//     perfectly valid session — so the problem body's code decides.
func (s *Server) writePlatformAPIResult(w http.ResponseWriter, resp backend.APIResponse) {
	switch {
	case resp.OK():
		writeAdminAPIBody(w, resp)

	case resp.StatusCode == http.StatusUnauthorized:
		isProd := s.bffConfig().IsProduction
		middleware.ClearPlatformSession(w, isProd)
		writeJSONError(w, http.StatusUnauthorized, "session_expired",
			"The platform-admin session has expired or was rejected by the backend. Sign in again.")

	case resp.StatusCode == http.StatusForbidden:
		problem, err := resp.Problem()
		if err == nil && problem.Code != "" && problem.Code != "access_denied" {
			// A domain FORBIDDEN, not an authorization denial — the session
			// is still perfectly valid. Let the caller see the real reason
			// (e.g. tenant.not_active) instead of being bounced to login.
			writeProblemError(w, resp.StatusCode, problem)
			return
		}
		isProd := s.bffConfig().IsProduction
		middleware.ClearPlatformSession(w, isProd)
		writeJSONError(w, http.StatusForbidden, "not_platform_admin",
			"This account no longer holds PLATFORM_ADMIN.")

	case resp.StatusCode == http.StatusBadRequest,
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
