// Package proxy is the BFF's transparent-relay mechanism for Surface 1
// (hosted end-user auth pages: /login, /logout, /branding, and — in later
// stages — /register, /verify-email/*, /magic-link/*, /password-reset/*,
// /oauth2/consent).
//
// This is genuinely different code from internal/backend's OAuthClient/
// AdminClient: those hold and interpret credentials on the BFF's own behalf
// (an OAuth2 client completing its own flow, or a bearer-authenticated admin
// API caller). A Proxy does neither — it relays a request/response pair
// byte-for-byte, unchanged, with NO BFF-side session state. That split is the
// stage's central design decision (see the package's routes.go callers for
// the rationale, and the stage report's CSRF section for why this surface
// carries no BFF-side CSRF check either).
package proxy

import (
	"io"
	"net/http"
	"strings"
	"time"
)

// hopByHopHeaders are connection-scoped (RFC 7230 §6.1) and must never be
// relayed verbatim between two independent HTTP connections (BFF↔browser,
// BFF↔backend) — each hop negotiates its own.
var hopByHopHeaders = map[string]struct{}{
	"Connection":          {},
	"Keep-Alive":          {},
	"Proxy-Authenticate":  {},
	"Proxy-Authorization": {},
	"Te":                  {},
	"Trailer":             {},
	"Transfer-Encoding":   {},
	"Upgrade":             {},
}

// Proxy relays requests to a single backend base (scheme+host+port+context
// path, e.g. http://localhost:9000/closeauth). One Proxy can serve any number
// of routes on that backend — construct once, call ServeTo per route with the
// backend-side path.
type Proxy struct {
	targetBase string
	httpClient *http.Client
}

// New constructs a Proxy targeting targetBase (backend base URL + context
// path — the caller assembles this from config.BackendConfig).
func New(targetBase string) *Proxy {
	return &Proxy{
		targetBase: strings.TrimRight(targetBase, "/"),
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			// Relay the backend's redirect AS a redirect to the browser —
			// never follow it on the BFF's behalf. Surface 1 is a pure relay;
			// following redirects here would silently turn a 302 (carrying a
			// Set-Cookie the browser needs) into something else entirely.
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

// RelayResult is a backend response captured in full (status, headers, body)
// WITHOUT writing anything to an http.ResponseWriter — the shape a caller
// that needs to INSPECT (not blindly relay) a response works with. Used by
// Deliverable 1 of Stage UI-2a (see internal/server/handlers_login_json.go):
// the JSON/fetch login mode reads a RelayResult to decide whether to
// translate a 302 into a JSON envelope or relay a 401 unchanged, rather than
// writing straight through the way ServeTo does.
type RelayResult struct {
	StatusCode int
	Header     http.Header
	Body       []byte
}

// Relay sends r (method, body, headers, cookies) to targetPath on the backend
// and returns the backend's response captured in full — same wire-level
// behavior as ServeTo (identical header/cookie forwarding rules), just
// handed back to the caller instead of written to a ResponseWriter. ServeTo
// is now a thin wrapper over this, so the pure-relay case and any
// translate-before-writing case share exactly one upstream call path.
func (p *Proxy) Relay(r *http.Request, targetPath string) (*RelayResult, error) {
	// The query string must be forwarded too — /login and /logout (the only
	// routes this relayed before Stage UI-2a) carry their data in a
	// form-encoded BODY, never a query string, so this was previously
	// untested and, it turns out, silently broken: GET /branding?client_id=…
	// (Stage UI-2a, Deliverable 2 — the first query-string-bearing route)
	// surfaced it immediately as a 500 (a required @RequestParam missing
	// server-side) until this fix. r.URL is nil-checked because Relay is also
	// called with a synthetic, URL-less *http.Request by the JSON login mode
	// (handlers_login_json.go) — that caller has no query string to forward.
	targetURL := p.targetBase + targetPath
	if r.URL != nil && r.URL.RawQuery != "" {
		targetURL += "?" + r.URL.RawQuery
	}
	upstreamReq, err := http.NewRequestWithContext(r.Context(), r.Method, targetURL, r.Body)
	if err != nil {
		return nil, err
	}
	upstreamReq.ContentLength = r.ContentLength
	CopyHeaders(upstreamReq.Header, r.Header)
	// Cookies are copied via AddCookie (not the raw Cookie header) so a
	// malformed incoming Cookie header can't be blindly forwarded verbatim,
	// and so there's exactly one place this route's cookie-forwarding
	// behavior is decided.
	for _, cookie := range r.Cookies() {
		upstreamReq.AddCookie(cookie)
	}

	resp, err := p.httpClient.Do(upstreamReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	return &RelayResult{StatusCode: resp.StatusCode, Header: resp.Header, Body: body}, nil
}

// ServeTo relays r (method, body, headers, cookies) to targetPath on the
// backend and writes the backend's response (status, body, headers including
// every Set-Cookie) back to w, unchanged. No BFF-side session state is read,
// written, or interpreted — this is a pure relay.
func (p *Proxy) ServeTo(w http.ResponseWriter, r *http.Request, targetPath string) {
	result, err := p.Relay(r, targetPath)
	if err != nil {
		http.Error(w, `{"error":"bad_gateway"}`, http.StatusBadGateway)
		return
	}

	CopyHeaders(w.Header(), result.Header)
	w.WriteHeader(result.StatusCode)
	_, _ = w.Write(result.Body)
}

// CopyHeaders copies every header from src to dst except hop-by-hop headers
// and Cookie (cookies are relayed explicitly via r.Cookies()/AddCookie so the
// jar's shape is always well-formed — see ServeTo). Multi-valued headers
// (critically Set-Cookie, which can appear more than once) are preserved in
// full via Add rather than Set. Exported so a caller inspecting (not
// blindly relaying) a RelayResult — e.g. the JSON/fetch login translation
// mode — copies headers through with the exact same hop-by-hop/Cookie
// filtering, rather than a second, potentially-diverging copy of this logic.
func CopyHeaders(dst, src http.Header) {
	for name, values := range src {
		if _, hopByHop := hopByHopHeaders[name]; hopByHop {
			continue
		}
		if strings.EqualFold(name, "Cookie") {
			continue
		}
		for _, value := range values {
			dst.Add(name, value)
		}
	}
}
