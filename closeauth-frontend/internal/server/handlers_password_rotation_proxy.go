package server

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"

	"closeauth-frontend/internal/proxy"
)

// Phase 4a: the JSON/fetch translation for the tenant-onboarding password-
// rotation confirm step, wired to POST /api/auth/password-rotation/confirm.
//
// Modeled directly on handlers_login_json.go's handleLoginJSON, because the
// backend's POST /password-rotation/confirm has the identical shape that
// motivated that translation in the first place: a 302 carrying a real
// Set-Cookie (CLOSEAUTH_SESSION — the ONLY point in the whole rotation flow
// a session is established) plus a Location the SPA must navigate to with a
// REAL top-level browser navigation, not something fetch() auto-follows
// cross-origin. A bare ServeTo relay (handlers_password_reset_proxy.go's
// pattern) is wrong here for exactly that reason — password-reset's confirm
// endpoint never redirects, this one always does on success.
//
// # The one deliberate divergence from handleLoginJSON's template
//
// handleLoginJSON explodes its authorizeQuery JSON field into SEPARATE
// top-level form parameters (url.ParseQuery + a per-key merge loop) because
// the backend's /login reads the full parameter map
// (LoginController.buildAuthorizeQuery / HttpServletRequest.getParameterMap).
// /password-rotation/confirm does NOT do that — PasswordRotationController.
// confirm declares a literal `@RequestParam(value = "authorize_query",
// required = false) String authorizeQuery` and hands it, as ONE opaque
// string, straight to LoginSuccessResponder.establishSessionAndResolveRedirect,
// which concatenates it raw onto "/oauth2/authorize?". Exploding it into
// separate form keys here would never reach that parameter at all — the
// confirm call would silently drop the resume context and the user would
// land on the bare BFF base URL instead of back in the app they were trying
// to sign into, with no error surfaced anywhere. So: set it as a single
// field, verbatim, never parsed.
type passwordRotationConfirmRequest struct {
	Token    string `json:"token"`
	Password string `json:"password"`
	ClientID string `json:"clientId"`
	// AuthorizeQuery is the SAME opaque, already-once-decoded string
	// PasswordRotationView.vue read via `new URLSearchParams(window.location.
	// search).get('authorize_query')` — carried through unparsed, unmodified,
	// see the type doc comment above for why.
	AuthorizeQuery string `json:"authorizeQuery"`
}

func (s *Server) handlePasswordRotationConfirm(w http.ResponseWriter, r *http.Request) {
	var payload passwordRotationConfirmRequest
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "Request body must be valid JSON.")
		return
	}
	if payload.Token == "" || payload.Password == "" || payload.ClientID == "" {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "token, password, and clientId are required.")
		return
	}

	form := url.Values{}
	form.Set("token", payload.Token)
	form.Set("password", payload.Password)
	form.Set("client_id", payload.ClientID)
	// Verbatim, single field — see the type doc comment. Omitted entirely
	// when empty (the emailed-link on-ramp carries none), matching the
	// backend's own `required = false` — PasswordRotationController treats
	// an absent authorize_query as valid, not as an error.
	if payload.AuthorizeQuery != "" {
		form.Set("authorize_query", payload.AuthorizeQuery)
	}
	encodedForm := form.Encode()

	// Synthetic upstream-shaped request — same construction as
	// handleLoginJSON's, for the same reason: proxy.Relay only reads
	// Method/Body/ContentLength/Header/Cookies() off whatever *http.Request
	// it's handed, so a URL-less synthetic request is sufficient.
	upstream := &http.Request{
		Method: http.MethodPost,
		Header: make(http.Header),
		Body:   io.NopCloser(strings.NewReader(encodedForm)),
	}
	upstream = upstream.WithContext(r.Context())
	upstream.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	upstream.ContentLength = int64(len(encodedForm))
	for _, cookie := range r.Cookies() {
		upstream.AddCookie(cookie)
	}

	result, err := s.authProxy.Relay(upstream, "/password-rotation/confirm")
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}

	// Relay every header unconditionally — critically Set-Cookie, the
	// CLOSEAUTH_SESSION cookie the confirm call establishes on success.
	proxy.CopyHeaders(w.Header(), result.Header)

	if result.StatusCode == http.StatusFound {
		redirectTo := result.Header.Get("Location")
		// Same fix as handleLoginJSON: the backend's 302 carries its own
		// (empty-body) Content-Length and a Location, both copied above by
		// CopyHeaders and both wrong for the JSON envelope written here — a
		// stale Content-Length: 0 would silently truncate the real body.
		w.Header().Del("Content-Length")
		w.Header().Del("Location")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"redirectTo": redirectTo})
		return
	}

	// Not a redirect — the uniform, non-enumerating 400 on an invalid/
	// expired/consumed token or unresolvable client_id, or anything else the
	// backend returned. Relayed through completely unchanged.
	w.WriteHeader(result.StatusCode)
	_, _ = w.Write(result.Body)
}
