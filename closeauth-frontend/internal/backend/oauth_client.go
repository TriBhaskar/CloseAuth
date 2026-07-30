// Package backend is the Go BFF's client of the CloseAuth backend: the real
// Authorization Code + PKCE flow (OAuthClient, this file), the bearer-
// authenticated admin API (AdminClient, admin_client.go), and the shapes they
// both hand back (Session, session.go; Claims, jwt.go; Problem, problem.go).
//
// Naming/design choices vs. the Java IT module's OAuthFlowClient (the
// reference this was ported from, not copied verbatim):
//   - CookieJar (map[string]string) replaces Java's raw Map<String,String> —
//     same shape, named so its role (the browser-like cookie jar carried
//     across a flow) is obvious at call sites. SessionState didn't need a
//     separate wrapper type in Go since CookieJar is already immutable-by-
//     convention here (every method returns a NEW jar rather than mutating
//     the one passed in — see (CookieJar).clone — so a caller can replay an
//     old jar against a different client exactly like Java's SessionState).
//   - Outcome/AuthorizeResult replace Outcome/AuthorizeOutcome — trimmed to
//     the three real outcomes this stage's proof needs to distinguish
//     (CODE_ISSUED / LOGIN_REQUIRED / CONSENT_REQUIRED classification is kept
//     because "observe what happened, don't blindly follow" is a load-
//     bearing design decision per the stage prompt, even though this stage's
//     own proof only exercises the LOGIN_REQUIRED → CODE_ISSUED path).
//   - No RestAssured-style "urlEncodingEnabled(false)" workaround: Go's
//     net/http never re-encodes a URL string you hand it directly to
//     http.NewRequest, so the double-encoding pitfall the Java client had to
//     name and work around doesn't exist here — nothing to port.
//   - http.Client.CheckRedirect returning http.ErrUseLastResponse is Go's
//     direct equivalent of RestAssured's `.redirects().follow(false)`.
package backend

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Outcome classifies how an /oauth2/authorize hit resolved.
type Outcome int

const (
	OutcomeUnknown Outcome = iota
	// OutcomeCodeIssued: SSO recognized the session (or the client is
	// trusted/pre-consented) — 302 straight to the client's redirect_uri
	// carrying an authorization code.
	OutcomeCodeIssued
	// OutcomeLoginRequired: no recognized session — 302 to /login.
	OutcomeLoginRequired
	// OutcomeConsentRequired: session recognized but this (non-trusted)
	// client needs an explicit consent decision — 302 to /oauth2/consent.
	OutcomeConsentRequired
)

func (o Outcome) String() string {
	switch o {
	case OutcomeCodeIssued:
		return "CODE_ISSUED"
	case OutcomeLoginRequired:
		return "LOGIN_REQUIRED"
	case OutcomeConsentRequired:
		return "CONSENT_REQUIRED"
	default:
		return "UNKNOWN"
	}
}

// CookieJar is a browser-like cookie jar (name -> value) carried across a
// flow. Every OAuthClient method that mutates the "browser state" returns a
// NEW CookieJar rather than mutating the one it was given, so a caller can
// replay an earlier jar against a later call (e.g. present a post-login jar
// to a second client to prove cross-client SSO / cross-tenant SSO refusal)
// without it being silently advanced underneath them.
type CookieJar map[string]string

func (j CookieJar) clone() CookieJar {
	out := make(CookieJar, len(j))
	for k, v := range j {
		out[k] = v
	}
	return out
}

// SessionKey returns the CLOSEAUTH_SESSION cookie value (also the DB
// auth_server_sessions.session_key), or "" if the jar has no session cookie
// yet (e.g. before login).
func (j CookieJar) SessionKey() string {
	return j["CLOSEAUTH_SESSION"]
}

// TokenResponse is the parsed /oauth2/token response body.
type TokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token,omitempty"` // absent for public clients / platform-admin (N/A here)
	TokenType    string `json:"token_type"`
	Scope        string `json:"scope"`
	ExpiresIn    int64  `json:"expires_in,omitempty"`
}

// AuthorizeResult is the outcome of one /oauth2/authorize hit.
type AuthorizeResult struct {
	Outcome  Outcome
	Code     string // set only when Outcome == OutcomeCodeIssued
	Location string // the raw Location header, for callers that need more than the classification
	Jar      CookieJar
}

// LoginResult is a completed password-login: the exchanged tokens plus the
// cookie jar the flow established (so a caller can reuse the session or hand
// it to a different client, mirroring the Java module's SessionState reuse).
type LoginResult struct {
	Tokens  TokenResponse
	Session CookieJar
}

// OAuthClient drives the real Authorization Code + PKCE flow against a
// running CloseAuth backend, exactly as a browser-based client would. It is
// the highest-reuse piece of this package: PKCE generation, a cookie jar
// threaded across requests, and manual redirect inspection (never auto-
// followed — the whole point is observing *which* redirect happened).
type OAuthClient struct {
	baseURL     string // e.g. http://localhost:32871 (no trailing slash, no context path)
	contextPath string // e.g. "/closeauth"
	redirectURI string // must match the registered client's redirect_uri
	httpClient  *http.Client
}

// NewOAuthClient constructs a client against a running backend. redirectURI
// is the callback URL the flow expects — it must equal a redirect_uri
// registered on every client this OAuthClient drives.
func NewOAuthClient(baseURL, contextPath, redirectURI string) *OAuthClient {
	return &OAuthClient{
		baseURL:     strings.TrimRight(baseURL, "/"),
		contextPath: contextPath,
		redirectURI: redirectURI,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				// Never auto-follow: the caller needs to see the Location header
				// itself to classify the outcome (SSO recognized vs. login
				// required vs. consent required).
				return http.ErrUseLastResponse
			},
		},
	}
}

func (c *OAuthClient) url(path string) string {
	return c.baseURL + c.contextPath + path
}

type rawResult struct {
	StatusCode int
	Location   string
	Body       []byte
	Jar        CookieJar
}

func (c *OAuthClient) do(ctx context.Context, method, rawURL string, body io.Reader, contentType, accept string, jar CookieJar) (rawResult, error) {
	req, err := http.NewRequestWithContext(ctx, method, rawURL, body)
	if err != nil {
		return rawResult{}, fmt.Errorf("oauth client: build request: %w", err)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if accept != "" {
		req.Header.Set("Accept", accept)
	}
	for name, value := range jar {
		req.AddCookie(&http.Cookie{Name: name, Value: value})
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return rawResult{}, fmt.Errorf("oauth client: %s %s: %w", method, rawURL, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return rawResult{}, fmt.Errorf("oauth client: read response body: %w", err)
	}

	newJar := jar.clone()
	for _, cookie := range resp.Cookies() {
		newJar[cookie.Name] = cookie.Value
	}

	return rawResult{
		StatusCode: resp.StatusCode,
		Location:   resp.Header.Get("Location"),
		Body:       respBody,
		Jar:        newJar,
	}, nil
}

func (c *OAuthClient) postForm(ctx context.Context, path string, form url.Values, jar CookieJar) (rawResult, error) {
	return c.do(ctx, http.MethodPost, c.url(path), strings.NewReader(form.Encode()), "application/x-www-form-urlencoded", "", jar)
}

// Authorize hits GET /oauth2/authorize carrying jar, WITHOUT logging in, and
// classifies the response. It does not mutate jar; the result carries the
// (possibly updated) jar to thread into a follow-up call.
func (c *OAuthClient) Authorize(ctx context.Context, clientID, scope string, pkce PKCE, state string, jar CookieJar) (AuthorizeResult, error) {
	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", clientID)
	q.Set("redirect_uri", c.redirectURI)
	q.Set("scope", scope)
	q.Set("code_challenge", pkce.Challenge)
	q.Set("code_challenge_method", "S256")
	q.Set("state", state)

	// Accept text/html so an unrecognized session 302s to /login (a JSON
	// Accept header would instead hit the resource-server chain and 401).
	result, err := c.do(ctx, http.MethodGet, c.url("/oauth2/authorize")+"?"+q.Encode(), nil, "", "text/html", jar)
	if err != nil {
		return AuthorizeResult{}, err
	}
	outcome, code := classifyAuthorize(result.Location, c.redirectURI)
	return AuthorizeResult{Outcome: outcome, Code: code, Location: result.Location, Jar: result.Jar}, nil
}

func classifyAuthorize(location, redirectURI string) (Outcome, string) {
	switch {
	case location == "":
		return OutcomeUnknown, ""
	case strings.HasPrefix(location, redirectURI):
		return OutcomeCodeIssued, queryParam(location, "code")
	case strings.Contains(location, "/oauth2/consent"):
		return OutcomeConsentRequired, ""
	case strings.Contains(location, "/login"):
		return OutcomeLoginRequired, ""
	default:
		return OutcomeUnknown, ""
	}
}

// Login drives the whole flow for a fresh password login: unauthenticated
// /authorize → /login → resume /authorize (now SSO-recognized) → capture the
// code → /oauth2/token. Returns the tokens and the resulting cookie jar.
//
// Cross-origin login continuity (CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md
// §3a): LoginController no longer falls back to a session-correlated
// SavedRequest to reconstruct the post-login /oauth2/authorize redirect —
// the full original authorize parameter set must be carried forward
// explicitly on POST /login's own form body (exactly what a real hosted
// login page's hidden form fields, or the BFF's authorizeQuery merge,
// would supply). This method models a direct, same-origin form POST (not
// the BFF's JSON-mode relay), so it supplies those fields itself, reusing
// the very values it already generated for step 1's /authorize call.
func (c *OAuthClient) Login(ctx context.Context, clientID, clientSecret, email, password string) (LoginResult, error) {
	pkce, err := NewPKCE()
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: generate PKCE: %w", err)
	}
	state := "st-" + uuid.NewString()
	const scope = "openid"

	// 1. Unauthenticated /authorize → 302 to /login (saves the request; sets the servlet SESSION cookie).
	init, err := c.Authorize(ctx, clientID, scope, pkce, state, CookieJar{})
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: step 1 (authorize): %w", err)
	}
	if init.Outcome != OutcomeLoginRequired {
		return LoginResult{}, fmt.Errorf("oauth login: step 1: expected LOGIN_REQUIRED, got %s (location=%q)", init.Outcome, init.Location)
	}
	jar := init.Jar

	// 2. POST /login → 302 back to a freshly reconstructed /oauth2/authorize URL (sets CLOSEAUTH_SESSION).
	// The non-credential fields here are exactly the same original authorize
	// parameters step 1 sent — LoginController reconstructs the resume URL
	// from these, not from any session-correlated saved request.
	form := url.Values{
		"email":                 {email},
		"password":              {password},
		"client_id":             {clientID},
		"redirect_uri":          {c.redirectURI},
		"response_type":         {"code"},
		"scope":                 {scope},
		"state":                 {state},
		"code_challenge":        {pkce.Challenge},
		"code_challenge_method": {"S256"},
	}
	loginResp, err := c.postForm(ctx, "/login", form, jar)
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: step 2 (login): %w", err)
	}
	if loginResp.StatusCode != http.StatusFound || !strings.Contains(loginResp.Location, "/oauth2/authorize") {
		return LoginResult{}, fmt.Errorf("oauth login: step 2: expected 302 to the saved /oauth2/authorize request, got HTTP %d location=%q body=%s",
			loginResp.StatusCode, loginResp.Location, loginResp.Body)
	}
	jar = loginResp.Jar

	// 3. Follow the resume URL (now carrying the session cookie) → 302 to the client callback with a code.
	resumeURL, err := c.resolveLocation(loginResp.Location)
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: step 3: resolve resume location: %w", err)
	}
	resumeResp, err := c.do(ctx, http.MethodGet, resumeURL, nil, "", "text/html", jar)
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: step 3 (resume authorize): %w", err)
	}
	if resumeResp.StatusCode != http.StatusFound || !strings.HasPrefix(resumeResp.Location, c.redirectURI) {
		return LoginResult{}, fmt.Errorf("oauth login: step 3: expected 302 to the client callback with a code, got HTTP %d location=%q",
			resumeResp.StatusCode, resumeResp.Location)
	}
	code := queryParam(resumeResp.Location, "code")
	if code == "" {
		return LoginResult{}, fmt.Errorf("oauth login: step 3: no authorization code in callback %q", resumeResp.Location)
	}
	jar = resumeResp.Jar

	// 4. Exchange the code for tokens.
	tokens, err := c.Exchange(ctx, clientID, clientSecret, code, pkce.Verifier)
	if err != nil {
		return LoginResult{}, fmt.Errorf("oauth login: step 4 (exchange): %w", err)
	}
	return LoginResult{Tokens: tokens, Session: jar}, nil
}

// Exchange trades an authorization code for tokens.
func (c *OAuthClient) Exchange(ctx context.Context, clientID, clientSecret, code, codeVerifier string) (TokenResponse, error) {
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {c.redirectURI},
		"code_verifier": {codeVerifier},
	}
	return c.tokenRequest(ctx, clientID, clientSecret, form)
}

// Refresh trades a refresh token for a new token pair (rotation: the backend
// issues a new refresh token too and invalidates the old one — see
// docs/backend §"token" module). Confidential clients only (SAS does not
// issue refresh tokens to public clients).
func (c *OAuthClient) Refresh(ctx context.Context, clientID, clientSecret, refreshToken string) (TokenResponse, error) {
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {refreshToken},
	}
	return c.tokenRequest(ctx, clientID, clientSecret, form)
}

func (c *OAuthClient) tokenRequest(ctx context.Context, clientID, clientSecret string, form url.Values) (TokenResponse, error) {
	if clientSecret == "" {
		form.Set("client_id", clientID) // public client identifies itself in the body
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url("/oauth2/token"), strings.NewReader(form.Encode()))
	if err != nil {
		return TokenResponse{}, fmt.Errorf("token request: build: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if clientSecret != "" {
		req.SetBasicAuth(clientID, clientSecret)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return TokenResponse{}, fmt.Errorf("token request: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return TokenResponse{}, fmt.Errorf("token request: read body: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return TokenResponse{}, fmt.Errorf("token request: HTTP %d: %s", resp.StatusCode, string(body))
	}
	var tokens TokenResponse
	if err := json.Unmarshal(body, &tokens); err != nil {
		return TokenResponse{}, fmt.Errorf("token request: decode response: %w", err)
	}
	return tokens, nil
}

// Logout runs RP-initiated logout (POST /logout) carrying the given jar's
// cookies, triggering the backend's four-leg revoke cascade. Returns the raw
// status (204 with no post_logout_redirect_uri; 302 if a registered one is
// supplied — not exercised here).
func (c *OAuthClient) Logout(ctx context.Context, clientID string, jar CookieJar) (int, error) {
	form := url.Values{"client_id": {clientID}}
	result, err := c.postForm(ctx, "/logout", form, jar)
	if err != nil {
		return 0, fmt.Errorf("logout: %w", err)
	}
	if result.StatusCode != http.StatusNoContent && result.StatusCode != http.StatusFound {
		return result.StatusCode, fmt.Errorf("logout: unexpected HTTP %d: %s", result.StatusCode, result.Body)
	}
	return result.StatusCode, nil
}

// resolveLocation makes a Location header value absolute against baseURL if
// it isn't already (Spring typically returns an absolute URL here since it's
// derived from the incoming request's own scheme/host, but this is cheap
// insurance against a relative one).
func (c *OAuthClient) resolveLocation(location string) (string, error) {
	u, err := url.Parse(location)
	if err != nil {
		return "", err
	}
	if u.IsAbs() {
		return location, nil
	}
	base, err := url.Parse(c.baseURL)
	if err != nil {
		return "", err
	}
	return base.ResolveReference(u).String(), nil
}

func queryParam(rawURL, name string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	return u.Query().Get(name)
}
