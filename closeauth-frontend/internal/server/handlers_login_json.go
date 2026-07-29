package server

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"closeauth-frontend/internal/proxy"
)

// Stage UI-2a, Deliverable 1: the redirect-vs-JSON-error translation
// mechanism for a JSON/fetch-mode login, wired to POST /api/auth/login.
//
// This is a DISTINCT route and handler from handlers_auth_proxy.go's
// handleLoginProxy (POST /login) — deliberately: /login stays exactly the
// pure-relay, raw-HTTP-form-POST target it already is (a future stage,
// magic-link consumption, needs that pure-navigation behavior unchanged), and
// this route is a genuinely different shape of client interaction: a Vue SPA
// form calling fetch() and needing to distinguish "redirect, please navigate"
// from "inline error, please render" without ever letting fetch() try to
// auto-follow a redirect that might cross to a third-party origin (the
// relying party's own redirect_uri).
//
// # Why a distinct path, not an Accept-header switch on the same /login path
//
// Both are legitimate per the stage prompt; a distinct path was chosen
// because it makes the two modes trivially greppable/testable/cacheable as
// separate routes (no content-negotiation branching inside one handler that
// two totally different response shapes would otherwise share), and it keeps
// /login's existing pure-relay handler (and its no-CSRF reasoning, see
// handlers_auth_proxy.go) untouched — a new handler function, not a new
// branch inside the old one. The `/api/` prefix matches the existing
// convention for BFF-owned (non-relay) JSON endpoints (see /api/health in
// routes.go).
//
// # The translation, precisely
//
//   - Backend 302 (success): the Location header becomes a 200 JSON envelope
//     {"redirectTo": "<url>"}. Any Set-Cookie the backend issued (the session
//     cookie) is relayed unchanged, so the browser has it before it performs
//     the follow-up top-level navigation Vue triggers via
//     `window.location.href = redirectTo`. That navigation is a REAL browser
//     navigation (not something fetch() follows), so it safely reaches
//     /oauth2/authorize on the backend's own origin — and beyond that,
//     potentially the relying party's own third-party redirect_uri — exactly
//     like a native form POST would have.
//   - Backend 401 (failure) or any other non-redirect status: relayed
//     through completely unchanged (status, JSON body, headers) — this is
//     already exactly what the Vue form wants to catch and render inline, no
//     translation needed.
type loginJSONRequest struct {
	Email      string `json:"email"`
	Password   string `json:"password"`
	RememberMe bool   `json:"rememberMe"`
	ClientID   string `json:"clientId"`
}

func (s *Server) handleLoginJSON(w http.ResponseWriter, r *http.Request) {
	var payload loginJSONRequest
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "Request body must be valid JSON.")
		return
	}
	if payload.Email == "" || payload.Password == "" {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "email and password are required.")
		return
	}

	form := url.Values{}
	form.Set("email", payload.Email)
	form.Set("password", payload.Password)
	form.Set("remember_me", strconv.FormatBool(payload.RememberMe))
	if payload.ClientID != "" {
		form.Set("client_id", payload.ClientID)
	}
	encodedForm := form.Encode()

	// Build a synthetic upstream-shaped request: the backend's /login expects
	// application/x-www-form-urlencoded, not the JSON body the SPA sent us,
	// so this is a genuine transcoding, not a pass-through. proxy.Relay only
	// reads Method/Body/ContentLength/Header/Cookies() off whatever *http.
	// Request it's handed — it never dereferences a URL — so this synthetic
	// request needs none of the fields ServeTo's caller (a real incoming
	// http.Request) otherwise has for free.
	upstream := &http.Request{
		Method: http.MethodPost,
		Header: make(http.Header),
		Body:   io.NopCloser(strings.NewReader(encodedForm)),
	}
	upstream = upstream.WithContext(r.Context())
	upstream.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	upstream.ContentLength = int64(len(encodedForm))
	// Cookies from the original request (there normally are none pre-login,
	// but forwarding them is the same convention proxy.Relay applies to every
	// other route) are carried across via AddCookie, same discipline as
	// ServeTo — never a raw, unvalidated Cookie header.
	for _, cookie := range r.Cookies() {
		upstream.AddCookie(cookie)
	}

	result, err := s.authProxy.Relay(upstream, "/login")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}

	// Relay every header (critically Set-Cookie, the session cookie) through
	// unconditionally on BOTH branches below — a 401 carries no Set-Cookie in
	// practice, but there's no reason to special-case that; this translation
	// layer stays as close to "relay, except for the one thing that must
	// change" as possible.
	proxy.CopyHeaders(w.Header(), result.Header)

	if result.StatusCode == http.StatusFound {
		redirectTo := result.Header.Get("Location")
		// The backend's 302 carried its own Content-Length (its redirect body
		// is empty) and a Location header — both copied above by CopyHeaders,
		// both wrong for the JSON envelope actually being written here: a
		// stale Content-Length: 0 would make net/http silently truncate the
		// real body to zero bytes, and Location has no meaning on a 200.
		w.Header().Del("Content-Length")
		w.Header().Del("Location")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"redirectTo": redirectTo})
		return
	}

	// Not a redirect (the uniform 401 invalid_credentials, or anything else
	// the backend returned) — relay completely unchanged.
	w.WriteHeader(result.StatusCode)
	_, _ = w.Write(result.Body)
}

func writeJSONError(w http.ResponseWriter, status int, code, description string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code, "error_description": description})
}
