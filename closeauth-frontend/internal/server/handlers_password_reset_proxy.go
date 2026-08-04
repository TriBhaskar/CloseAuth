package server

import "net/http"

// Stage UI-2c-i, Deliverable 1: password reset's request + confirm steps,
// wired exactly like handlers_registration_proxy.go — thin Relay pass-
// throughs, no translation mechanism needed. Neither ever redirects
// (confirmed against PasswordResetController source):
//   - POST /password-reset/request → always a bare 200 (enumeration-safe).
//   - POST /password-reset/confirm → 200 on a successful reset (which also
//     revokes every session/token server-side — the post-reset cascade), or
//     a generic 400 on an invalid/expired/used token (never says which).
//
// Unlike magic-link, password reset's emailed link DOES need a BFF-hosted
// Vue route to land on (Design Decision #2) — that's ResetPasswordView.vue,
// not anything in this file; this file only relays the two JSON/form API
// calls that view's form makes.
func (s *Server) handlePasswordResetRequestProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/password-reset/request")
}

func (s *Server) handlePasswordResetConfirmProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/password-reset/confirm")
}
