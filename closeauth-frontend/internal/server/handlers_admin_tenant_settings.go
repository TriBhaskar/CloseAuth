package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3e: tenant branding and registration-config administration — the
// BFF's FIRST PUT handlers. Same shape as every other admin-CRUD handler
// (handlers_admin_users.go's preamble): chi.URLParam("slug") ->
// s.adminSessionOrError -> (readJSONBody for the mutations) ->
// s.adminClient.Get/PutJSON against /v1/tenants/{session.TenantID}/... ->
// s.writeAdminAPIResult. The body is forwarded byte-for-byte as a
// json.RawMessage on PUT, same as every other mutation in this package — the
// BFF mirrors no backend DTOs (UpdateBrandingCommand,
// UpdateRegistrationConfigCommand) here.
//
// AdminClient.PutJSON (internal/backend/admin_client.go) already existed —
// used only by testsupport.Fixtures.SetRegistrationMode before this stage —
// so no new admin-client method was needed to add these routes.
//
// Two backend contract facts worth knowing when reading (or debugging) these
// handlers, both belonging to the SPA layer, not this file, but recorded
// here since they explain why these handlers do nothing clever:
//   - Branding PUT is a full replacement (TenantBrandingService.updateBranding
//     sets all five columns unconditionally) — the BFF forwards whatever body
//     the SPA sent, so it's the SPA's job (tenantAdminBranding.ts) to always
//     send all five fields, never a sparse patch.
//   - An unrecognised registration-config `mode` string produces a backend
//     500 (Jackson HttpMessageNotReadableException, no dedicated handler in
//     ApiExceptionHandler), not a 400 — writeAdminAPIResult's default case
//     forwards that as bad_gateway, un-special-cased; the SPA's constrained
//     <select> is what actually prevents an admin from triggering it.

// ---- branding -------------------------------------------------------------

func (s *Server) handleAdminBrandingGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/branding")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminBrandingUpdate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PutJSON(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/branding", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

// ---- registration config ---------------------------------------------------

func (s *Server) handleAdminRegistrationConfigGet(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.Get(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/registration-config")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}

func (s *Server) handleAdminRegistrationConfigUpdate(w http.ResponseWriter, r *http.Request) {
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
	resp, err := s.adminClient.PutJSON(r.Context(), session.AccessToken, "/v1/tenants/"+session.TenantID+"/registration-config", body)
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
