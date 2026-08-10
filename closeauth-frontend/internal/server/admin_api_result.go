package server

import (
	"encoding/json"
	"net/http"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/middleware"
)

// Stage UI-3b: the shared response mapper every admin-CRUD handler
// (handlers_admin_users.go, and whatever clients/roles/branding/audit add
// later) calls to turn a backend AdminClient APIResponse into the BFF's
// house JSON envelope. Extracted from handlers_admin_ping.go's original
// inline switch, which collapsed every non-2xx/401/403 response into a
// generic "bad_gateway" — meaning an RFC 7807 domain code (e.g.
// "tenant_role.last_admin") or a field-level validation errors map could
// never reach the SPA. That's fatal for this stage: the whole point of the
// last-admin invariant is that the UI shows the SPECIFIC reason, not a
// generic conflict banner.
//
// Mapping:
//   - 2xx           -> passed through verbatim (status + body); the SPA
//     decodes PageView<T>/UserView/etc. straight off the backend's own
//     shape, no BFF-side reshaping.
//   - 401           -> writeReauthRequired: RequireAdminSession thought the
//     token was healthy: the backend disagrees (revoked out-of-band).
//   - 403 whose problem code is "access_denied" (or unparseable/blank —
//     the pre-UI-3b behavior when no problem body was available) -> the
//     cross-tenant/role-revoked-mid-session case: clear the session, record
//     a denial marker, "not_tenant_admin". Matches handlers_admin_ping.go's
//     original behavior exactly.
//   - 403 with any OTHER code (e.g. "role.system_immutable") -> forwarded
//     as an ordinary error WITHOUT touching the session. This split matters:
//     blanket-clearing the session on every 403 would sign an admin out for
//     touching an immutable system role, which is a business-rule
//     rejection, not a standing failure.
//   - 400 / 404 / 409 -> forwarded with the domain code, detail, and (only
//     when present, i.e. validation 400s) the field->message errors map —
//     this is what lets the SPA's validationErrors/conflict result kinds
//     have real data to branch on, including preserving
//     "tenant_role.last_admin" intact through to the browser.
//   - anything else -> "bad_gateway".
func (s *Server) writeAdminAPIResult(w http.ResponseWriter, slug string, session *middleware.AdminSession, resp backend.APIResponse) {
	switch {
	case resp.OK():
		writeAdminAPIBody(w, resp)

	case resp.StatusCode == http.StatusUnauthorized:
		writeReauthRequired(w, slug)

	case resp.StatusCode == http.StatusForbidden:
		problem, _ := resp.Problem()
		if problem.Code == "" || problem.Code == "access_denied" {
			isProd := s.bffConfig().IsProduction
			middleware.ClearAdminSession(w, slug, isProd)
			_ = middleware.SetAdminDenied(w, slug, "not_tenant_admin", session.UserID, isProd)
			writeJSONError(w, http.StatusForbidden, "not_tenant_admin", "This account no longer has TENANT_ADMIN access to this tenant.")
			return
		}
		writeProblemError(w, resp.StatusCode, problem)

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

// writeAdminAPIBody passes a 2xx backend response through verbatim. A 204 (or
// any other body-less 2xx, e.g. the tenant-role assign/revoke endpoints) is
// forwarded as a bare status with no Content-Type — writing an empty
// "application/json" body would be misleading to a client expecting to
// json.Unmarshal a non-empty response.
func writeAdminAPIBody(w http.ResponseWriter, resp backend.APIResponse) {
	if resp.StatusCode == http.StatusNoContent || len(resp.Body) == 0 {
		w.WriteHeader(resp.StatusCode)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(resp.Body)
}

// adminProblemErrorBody is the house JSON error envelope (see writeJSONError)
// extended with the RFC 7807 field-level errors map, present only for
// validation failures.
type adminProblemErrorBody struct {
	Error            string         `json:"error"`
	ErrorDescription string         `json:"error_description"`
	Errors           map[string]any `json:"errors,omitempty"`
}

// writeProblemError forwards a parsed backend Problem's domain code, detail,
// and (when present) field-level errors map, preserving exactly the
// information src/api/tenantAdminProblem.ts needs to distinguish
// validationErrors from a named conflict like "tenant_role.last_admin" from
// a generic error.
func writeProblemError(w http.ResponseWriter, status int, problem backend.Problem) {
	code := problem.Code
	if code == "" {
		code = "backend_error"
	}
	detail := problem.Detail
	if detail == "" {
		detail = problem.Title
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(adminProblemErrorBody{
		Error:            code,
		ErrorDescription: detail,
		Errors:           problem.Errors,
	})
}
