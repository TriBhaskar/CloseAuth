package server

import "net/http"

// Stage UI-2b, Deliverable 1: three more pure-relay additions to Surface 1,
// wired exactly like handlers_branding_proxy.go / handlers_auth_proxy.go —
// none of these ever redirect (API_REFERENCE.md §1: POST /register,
// /verify-email/request, /verify-email/confirm all resolve to a plain JSON
// body or an empty-bodied status code), so no translation mechanism
// (handlers_login_json.go's kind) is needed here. The backend consumes
// application/x-www-form-urlencoded for all three (confirmed against
// RegistrationController/EmailVerificationController source, not assumed
// from the docs alone) — the Vue side's dedicated fetch calls post
// form-urlencoded bodies directly, so the byte-for-byte relay is correct
// as-is with no request-side transcoding either.
func (s *Server) handleRegisterProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/register")
}

func (s *Server) handleVerifyEmailRequestProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/verify-email/request")
}

func (s *Server) handleVerifyEmailConfirmProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/verify-email/confirm")
}
