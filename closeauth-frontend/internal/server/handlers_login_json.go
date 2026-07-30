package server

import (
	"encoding/json"
	"io"
	"log/slog"
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
	// AuthorizeQuery is the raw query string LoginView.vue captured off
	// window.location.search when the login page loaded — the entire
	// original /oauth2/authorize request's parameters (client_id,
	// redirect_uri, response_type, scope, state, code_challenge,
	// code_challenge_method, plus any OIDC extras), carried as ONE opaque
	// string rather than named fields. See
	// CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3b: the backend and BFF are on
	// genuinely separate origins with no reverse proxy, so the servlet
	// session cookie a same-origin browser would rely on to resume the
	// original /oauth2/authorize hit never reaches the BFF at all — this
	// field is what makes the reconstruction in LoginController /
	// LoginSuccessResponder possible without it.
	AuthorizeQuery string `json:"authorizeQuery"`
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
	// Merge the captured original /oauth2/authorize query string into the SAME
	// top-level form — a single-level, correctly form-encoded body, not a
	// nested/double-encoded blob. Setting the raw string as one field's value
	// and letting form.Encode() run would percent-encode the entire query
	// string as one opaque value, which the backend would then have to decode
	// a second time rather than reading real, separate parameters; the
	// backend's own LoginController.login() reads named @RequestParam fields
	// plus HttpServletRequest.getParameterMap() directly, unmodified by this
	// change, so this merge is what lets that work as-is.
	//
	// Keys already populated above (currently just client_id, since
	// email/password/remember_me are never legitimate /oauth2/authorize
	// parameters and would never appear in a real authorizeQuery) are left
	// alone rather than appended-to: LoginView.vue derives its own
	// clientId field from the very same captured query string, so the two
	// would otherwise always carry an identical, redundant duplicate value —
	// which Spring's @RequestParam String binding would then see as a
	// multi-value parameter and could mangle (e.g. comma-joining) rather than
	// silently ignoring. Skipping already-set keys keeps the outgoing form a
	// clean single value per key.
	if payload.AuthorizeQuery != "" {
		trimmed := strings.TrimPrefix(payload.AuthorizeQuery, "?")
		parsed, err := url.ParseQuery(trimmed)
		if err != nil {
			// Malformed extra params shouldn't sink an otherwise-valid login
			// attempt — ignore and proceed with whatever was already set
			// (email/password/remember_me/client_id). slog.Default() rather
			// than s.logger: this handler is exercised in tests against a
			// zero-value *Server with no logger configured (see
			// login_json_test.go), and a nil *slog.Logger method call panics.
			slog.Default().Warn("authorizeQuery failed to parse, ignoring", "error", err)
		} else {
			for key, values := range parsed {
				if _, alreadySet := form[key]; alreadySet {
					continue
				}
				for _, value := range values {
					form.Add(key, value)
				}
			}
		}
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
