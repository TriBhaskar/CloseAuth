package server

import "net/http"

// Stage UI-2c-i, Deliverable 1: magic-link's REQUEST step only, wired exactly
// like handlers_registration_proxy.go — a thin Relay pass-through, no
// translation mechanism needed (POST /magic-link/request never redirects;
// it's always a bare 200, confirmed against MagicLinkController source).
//
// Magic-link's CONSUME step (GET /magic-link/consume) deliberately has no
// corresponding BFF route: Design Decision #1 (CLOSEAUTH_UI_STAGE_2c-i_
// PROMPT.md) — the emailed link points directly at the backend's own origin
// (properties.getIssuerUrl() + "/magic-link/consume?..."), never through the
// BFF, so the browser navigates straight there and the backend's own
// session-based resume mechanism handles it unassisted. There is nothing for
// this BFF to relay or translate on that side.
func (s *Server) handleMagicLinkRequestProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/magic-link/request")
}
