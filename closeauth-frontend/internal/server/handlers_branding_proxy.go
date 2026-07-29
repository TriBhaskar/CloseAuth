package server

import "net/http"

// Stage UI-2a, Deliverable 2 (Go side): a thin pure-relay addition for
// GET /branding, wired exactly like handlers_auth_proxy.go's login/logout
// pair — no translation needed here (unlike /api/auth/login), because
// GET /branding never redirects; it's always a plain 200 JSON body
// ({logoUrl, primaryColor, backgroundColor, accentColor, companyName} —
// API_REFERENCE.md §1). It's public/unauthenticated on the backend
// (permitAll, resolved client_id → tenant → branding with platform defaults
// filling unset fields), so — like /login and /logout — it carries no
// BFF-side session/CSRF state; it's registered alongside them in routes.go.
func (s *Server) handleBrandingProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/branding")
}
