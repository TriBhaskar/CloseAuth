package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3c: the tenant-admin console's client-registration surface —
// register (POST), a tenant-scoped get (GET), and secret regeneration
// (POST .../client-secret). Same shape as handlers_admin_users.go: every
// handler pulls the session via s.adminSessionOrError, validates any
// path-supplied id with validUUID before it reaches the backend URL, calls
// s.adminClient against /v1/tenants/{session.TenantID}/clients/** with the
// session's bearer token, and maps the result through s.writeAdminAPIResult.
//
// Deliberately NOT a mirrored command DTO: POST /clients forwards the
// request body byte-for-byte via readJSONBody, exactly like
// handleAdminUserCreate — the backend is the sole source of truth for
// validation. This is genuinely a thin relay here, unlike an earlier design
// that had the BFF mint the client secret itself: as of UI-3c the BACKEND
// generates the secret (ClientSecretGenerator, closeauth-backend) and
// ignores any caller-supplied value, so there was nothing left for the BFF
// to do beyond forwarding the create/regenerate responses through — both of
// which carry a plaintext secret exactly once, the one deliberate exception
// to the "no secret-shaped value ever reaches a response body unexamined"
// discipline (see assertNoTokenLeakExceptSecret in admin_console_test.go).
//
// No client LIST route exists here because none exists on the backend
// (TenantClientController implements only create + get — SAS's
// RegisteredClientRepository exposes no tenant-scoped list/delete; flagged,
// not silently built around). The SPA is expected to handle that gap
// honestly rather than this layer faking one.

// ---- clients ----------------------------------------------------------

func (s *Server) handleAdminClientCreate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PostJSON(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/clients", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminClientGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	clientID := chi.URLParam(r, "clientId")
	if !validUUID(clientID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_client_id", "Malformed client id.")
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/clients/"+clientID)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// handleAdminClientSecretRegenerate backs the missing recovery path this
// stage adds: a lost secret was previously unrecoverable (the client was
// simply dead). A bare POST with no body — the backend mints the new secret
// itself, returning it exactly once (ClientSecretView), just like create. A
// public client's regeneration attempt comes back as a 409
// "client.public_no_secret" from the backend; writeAdminAPIResult forwards
// that code intact rather than flattening it, exactly like the last-admin
// 409 in handlers_admin_users.go.
func (s *Server) handleAdminClientSecretRegenerate(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	clientID := chi.URLParam(r, "clientId")
	if !validUUID(clientID) {
		writeJSONError(w, http.StatusBadRequest, "invalid_client_id", "Malformed client id.")
		return
	}
	resp, err := s.adminClient.Post(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/clients/"+clientID+"/client-secret")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
