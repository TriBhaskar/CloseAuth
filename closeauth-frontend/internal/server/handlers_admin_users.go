package server

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"

	"closeauth-frontend/internal/middleware"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3b: the tenant-admin console's first real CRUD surface — user
// listing/creation/lifecycle and tenant-role assignment. Modelled on
// handlers_admin_ping.go, not the *_proxy.go family: every handler here
// pulls the session via middleware.AdminSessionFrom (populated by
// RequireAdminSession, the only middleware mounted on this route group),
// calls s.adminClient with that session's bearer token against
// /v1/tenants/{session.TenantID}/**, and maps the result through
// s.writeAdminAPIResult (admin_api_result.go) — never a client-supplied
// tenant id, so a browser can't address another tenant even with a valid
// session.
//
// Path-supplied {userId}/{roleId} are validated as UUIDs before being
// concatenated into the backend URL (validUUID, handlers_admin_auth.go);
// list endpoints' query strings are rewritten through an allow-list
// (pagingQuery, below) so only page/size — re-parsed as ints — ever reach
// the backend, never the browser's raw query string.

// maxAdminRequestBodyBytes bounds a JSON request body forwarded to the
// backend (currently only POST /users). Generous for a user-create payload;
// bounds an abusive/broken client rather than reflecting any real limit.
const maxAdminRequestBodyBytes = 1 << 20 // 1 MiB

var errInvalidJSONBody = errors.New("invalid JSON body")

// adminSessionOrError is the shared preamble every handler below starts
// with: the session (RequireAdminSession guarantees this exists, but fail
// closed rather than panic if a future refactor ever drops the middleware
// from a route) and the adminClient (nil in the always-run structural test
// fixtures in routes_admin_test.go, which build a zero-value *Server).
func (s *Server) adminSessionOrError(w http.ResponseWriter, r *http.Request) (*middleware.AdminSession, bool) {
	session, ok := middleware.AdminSessionFrom(r.Context())
	if !ok {
		writeJSONError(w, http.StatusUnauthorized, "unauthenticated", "No active tenant-admin session for this tenant.")
		return nil, false
	}
	if s.adminClient == nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Admin API client is not configured.")
		return nil, false
	}
	return session, true
}

// pagingQuery rebuilds page/size from the incoming request's query string,
// re-parsed as non-negative ints — an allow-list, not a filter: any other
// query parameter, and any non-numeric page/size, is silently dropped
// rather than forwarded. Malformed/absent values fall back to the backend's
// own defaults (page=0, size=20) rather than erroring — paging is a
// convenience, not a contract the SPA must get exactly right.
func pagingQuery(r *http.Request) url.Values {
	values := url.Values{}
	if page := r.URL.Query().Get("page"); page != "" {
		if n, err := strconv.Atoi(page); err == nil && n >= 0 {
			values.Set("page", strconv.Itoa(n))
		}
	}
	if size := r.URL.Query().Get("size"); size != "" {
		if n, err := strconv.Atoi(size); err == nil && n >= 0 {
			values.Set("size", strconv.Itoa(n))
		}
	}
	return values
}

// userFilterParams allow-lists the tenant users list's three filters
// (TenantUserController's own status/role/q request params, FE-4a) by their
// exact query-param names. Same "forward raw, let the backend validate"
// posture as auditFilterParams (handlers_admin_audit.go) — the backend turns
// a bad status enum value into a clean 400, and duplicating that validation
// here would just invent a second, differently-shaped error for the same bad
// input.
var userFilterParams = []string{"status", "role", "q"}

// userFilterQuery rebuilds the users-list query string from page/size (via
// pagingQuery) plus the three allow-listed filters above — any OTHER query
// parameter on the incoming request is silently dropped, never forwarded.
// Mirrors auditQuery's shape exactly (handlers_admin_audit.go).
func userFilterQuery(r *http.Request) url.Values {
	values := pagingQuery(r)
	incoming := r.URL.Query()
	for _, name := range userFilterParams {
		if v := incoming.Get(name); v != "" {
			values.Set(name, v)
		}
	}
	return values
}

// readJSONBody reads and validates the request body as JSON, returning it as
// a json.RawMessage so it can be forwarded to the backend byte-for-byte
// (json.RawMessage.MarshalJSON returns its bytes verbatim, so
// AdminClient.PostJSON's json.Marshal round-trips it unchanged) rather than
// decoded into a Go struct the BFF would have to keep in lockstep with the
// backend's command DTOs — the BFF stays a thin relay; the backend remains
// the sole source of truth for validation.
func readJSONBody(w http.ResponseWriter, r *http.Request) (json.RawMessage, error) {
	r.Body = http.MaxBytesReader(w, r.Body, maxAdminRequestBodyBytes)
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	if !json.Valid(data) {
		return nil, errInvalidJSONBody
	}
	return json.RawMessage(data), nil
}

// ---- users ----------------------------------------------------------------

func (s *Server) handleAdminUsersList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users", userFilterQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminUserCreate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// FE-4a: the temporary-password create mode (spec §6.4.2) — a distinct
// backend endpoint (TenantOnboardingService.createUserWithTempCredential),
// not a flag on POST /users. Body shape is the SPA's job to build correctly;
// the BFF stays a thin relay, same as handleAdminUserCreate above.
func (s *Server) handleAdminUserCreateWithTempCredential(w http.ResponseWriter, r *http.Request) {
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
		"/v1/tenants/"+session.TenantID+"/users/with-temp-credential", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminUserGet(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users/"+userID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminUserLifecycle backs suspend/activate/approve — all three are a
// bare POST with no body, differing only in the backend action segment.
func (s *Server) handleAdminUserLifecycle(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
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
		resp, err := s.adminClient.Post(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users/"+userID+"/"+action)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writeAdminAPIResult(w, slug, session, resp)
	}
}

func (s *Server) handleAdminUserDelete(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.Delete(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users/"+userID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// ---- tenant roles -----------------------------------------------------

func (s *Server) handleAdminRolesList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/roles", pagingQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminUserTenantRoles backs GET /users/{userId}/tenant-roles — the
// UI-3b backend addition (TenantRoleController.rolesForUser) that makes the
// role-assignment panel possible: a JSON array of role NAMES currently held,
// which the SPA joins against GET /roles for ids (see
// src/api/tenantAdminRoles.ts's HeldRole join type).
func (s *Server) handleAdminUserTenantRoles(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/users/"+userID+"/tenant-roles")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminUserRoleAssignment backs assign (POST) and revoke (DELETE) —
// identical shape, differing only in the AdminClient verb. A revoke that
// would drop the tenant's last active TENANT_ADMIN comes back as a 409
// "tenant_role.last_admin" from the backend; writeAdminAPIResult forwards
// that code intact rather than flattening it to a generic conflict.
func (s *Server) handleAdminUserRoleAssignment(method string) http.HandlerFunc {
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
		path := "/v1/tenants/" + session.TenantID + "/users/" + userID + "/tenant-roles/" + roleID

		if method == http.MethodPost {
			result, err := s.adminClient.Post(r.Context(), session.AccessToken, path)
			if err != nil {
				writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
				return
			}
			s.writeAdminAPIResult(w, slug, session, result)
			return
		}

		result, err := s.adminClient.Delete(r.Context(), session.AccessToken, path)
		if err != nil {
			writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
			return
		}
		s.writeAdminAPIResult(w, slug, session, result)
	}
}
