package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/google/uuid"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestLoginJSON_RedirectEnvelope_ResolvesToRealAuthorizationCode is Stage
// UI-2a Deliverable 1's required proof: the CloseAuth-specific risk isn't
// "does a browser execute window.location.href" (a basic web-platform
// guarantee) — it's whether the BFF's server-side translation (a backend 302
// → a JSON envelope carrying a URL that's ACTUALLY followable) is correct.
// So this test, against the REAL harness:
//  1. Drives an unauthenticated GET /oauth2/authorize directly at the real
//     backend (this route is never proxied by the BFF — browsers reach it
//     directly at the backend's own origin, confirmed against
//     AuthorizationServerConfig/LoginUrlAuthenticationEntryPoint — see the
//     stage report), saving the request and getting LOGIN_REQUIRED.
//  2. POSTs the new JSON login endpoint (through the BFF's real router),
//     carrying the servlet-session cookie from step 1, and asserts the
//     {"redirectTo": ...} envelope shape plus a relayed CLOSEAUTH_SESSION
//     Set-Cookie.
//  3. Manually follows redirectTo with a plain Go http.Client GET — carrying
//     forward both the servlet-session cookie AND the new CLOSEAUTH_SESSION
//     cookie, exactly mirroring internal/backend's own proven OAuthClient.
//     Login() step 2→3 handoff — and confirms it resolves to a REAL 302 at
//     the relying party's redirect_uri carrying an authorization code.
//  4. Exchanges that code for tokens, proving the code is genuinely valid,
//     not merely present in a URL.
//
// Updated for cross-origin login continuity (CLOSEAUTH_CROSS_ORIGIN_LOGIN_
// DESIGN.md §3a/§3b): LoginController/LoginSuccessResponder no longer
// consult Spring Security's session-correlated RequestCache/SavedRequest at
// all for password login (that fallback was deliberately deleted — see
// LoginController's own javadoc, "no session-correlated request-cache
// fallback") — carrying the servlet-session cookie forward from step 1 to
// step 2, as this test previously did, no longer resumes anything by
// itself, so this test now ALSO carries the original /oauth2/authorize
// query string forward as `authorizeQuery`, exactly as LoginView.vue does in
// production. See TestLoginJSON_AuthorizeQueryPassthrough_
// ResolvesToRealAuthorizationCode below for the dedicated, more thorough
// proof of that specific mechanism (including the genuinely-cross-origin,
// no-servlet-session-at-all case, and an arbitrary OIDC-extra parameter).
func TestLoginJSON_RedirectEnvelope_ResolvesToRealAuthorizationCode(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}
	const redirectURI = "http://localhost:23456/callback"
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, redirectURI)
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("login-json-user")
	password := "Login-Json-Pw-123!"
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
		t.Fatalf("create user: %v", err)
	}

	// ---- step 1: unauthenticated /oauth2/authorize, DIRECT against the backend ----
	oauth := stack.OAuthClient(redirectURI)
	pkce, err := backend.NewPKCE()
	if err != nil {
		t.Fatalf("generate PKCE: %v", err)
	}
	state := "st-" + uuid.NewString()
	initResult, err := oauth.Authorize(ctx, creds.ClientID, "openid", pkce, state, backend.CookieJar{})
	if err != nil {
		t.Fatalf("initial authorize: %v", err)
	}
	if initResult.Outcome != backend.OutcomeLoginRequired {
		t.Fatalf("expected LOGIN_REQUIRED on the unauthenticated authorize hit, got %s (location=%q)",
			initResult.Outcome, initResult.Location)
	}

	// ---- step 2: the BFF's real router, the JSON login endpoint under test ----
	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	loginPageURL, err := url.Parse(initResult.Location)
	if err != nil {
		t.Fatalf("parse entry-point redirect location: %v", err)
	}
	loginBody, err := json.Marshal(map[string]any{
		"email":          email,
		"password":       password,
		"clientId":       creds.ClientID,
		"rememberMe":     false,
		"authorizeQuery": loginPageURL.RawQuery,
	})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	loginReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/login", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatalf("build login request: %v", err)
	}
	loginReq.Header.Set("Content-Type", "application/json")
	// NOT carrying the servlet-session cookie from step 1 — per this
	// function's updated doc comment, LoginController no longer consults it
	// at all for password login; authorizeQuery above is what now carries
	// the original /oauth2/authorize parameters forward.

	loginResp, err := http.DefaultClient.Do(loginReq)
	if err != nil {
		t.Fatalf("POST /api/auth/login: %v", err)
	}
	defer loginResp.Body.Close()

	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 (translated JSON envelope) from the JSON login endpoint on valid credentials, got %d", loginResp.StatusCode)
	}
	var envelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(loginResp.Body).Decode(&envelope); err != nil {
		t.Fatalf("decode redirect envelope: %v", err)
	}
	if envelope.RedirectTo == "" {
		t.Fatalf("expected a non-empty redirectTo in the JSON envelope")
	}
	if !strings.Contains(envelope.RedirectTo, "/oauth2/authorize") {
		t.Fatalf("expected redirectTo to be a freshly reconstructed /oauth2/authorize request, got %q "+
			"(a bare platform-default fallback here would mean authorizeQuery didn't carry through)", envelope.RedirectTo)
	}
	var sessionCookie *http.Cookie
	for _, c := range loginResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected a CLOSEAUTH_SESSION cookie relayed from the backend's real Set-Cookie, got cookies: %v", loginResp.Cookies())
	}
	t.Logf("login JSON envelope: redirectTo=%s, relayed cookie %s (len=%d)",
		envelope.RedirectTo, sessionCookie.Name, len(sessionCookie.Value))

	// ---- step 3: manually follow redirectTo, DIRECT against the backend ----
	noRedirectClient := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resumeReq, err := http.NewRequest(http.MethodGet, envelope.RedirectTo, nil)
	if err != nil {
		t.Fatalf("build resume request: %v", err)
	}
	// Carry ONLY the new CLOSEAUTH_SESSION cookie (step 2) — TenantSessionSsoFilter
	// recognizes that cookie alone (never JSESSIONID/HttpSessionRequestCache),
	// exactly as a real same-origin browser navigation back to the backend
	// would; the step-1 servlet-session cookie is deliberately not carried
	// forward at all (see this function's updated doc comment).
	resumeReq.AddCookie(sessionCookie)

	resumeResp, err := noRedirectClient.Do(resumeReq)
	if err != nil {
		t.Fatalf("follow redirectTo: %v", err)
	}
	defer resumeResp.Body.Close()

	if resumeResp.StatusCode != http.StatusFound || !strings.HasPrefix(resumeResp.Header.Get("Location"), redirectURI) {
		t.Fatalf("expected 302 to the client callback (%s) with a code, got HTTP %d location=%q",
			redirectURI, resumeResp.StatusCode, resumeResp.Header.Get("Location"))
	}
	callbackURL, err := url.Parse(resumeResp.Header.Get("Location"))
	if err != nil {
		t.Fatalf("parse callback location: %v", err)
	}
	code := callbackURL.Query().Get("code")
	if code == "" {
		t.Fatalf("no authorization code in callback %q", resumeResp.Header.Get("Location"))
	}
	t.Logf("redirectTo chain resolved: 302 to %s with a real authorization code (len=%d)", redirectURI, len(code))

	// ---- step 4: exchange the code — proves it's genuinely valid, not just present ----
	tokens, err := oauth.Exchange(ctx, creds.ClientID, creds.Secret, code, pkce.Verifier)
	if err != nil {
		t.Fatalf("exchange code for tokens: %v", err)
	}
	if tokens.AccessToken == "" {
		t.Fatalf("expected an access token from the code exchange")
	}
	t.Logf("code exchange ok: token_type=%s scope=%q", tokens.TokenType, tokens.Scope)

	// ---- failure case: wrong password relays the backend's 401 unchanged ----
	badBody, err := json.Marshal(map[string]any{
		"email":    email,
		"password": "definitely-wrong",
		"clientId": creds.ClientID,
	})
	if err != nil {
		t.Fatalf("marshal bad-login body: %v", err)
	}
	badReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/login", bytes.NewReader(badBody))
	if err != nil {
		t.Fatalf("build bad-login request: %v", err)
	}
	badReq.Header.Set("Content-Type", "application/json")

	badResp, err := http.DefaultClient.Do(badReq)
	if err != nil {
		t.Fatalf("POST /api/auth/login (bad credentials): %v", err)
	}
	defer badResp.Body.Close()

	if badResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 relayed unchanged on bad credentials, got %d", badResp.StatusCode)
	}
	var errBody map[string]string
	if err := json.NewDecoder(badResp.Body).Decode(&errBody); err != nil {
		t.Fatalf("decode error body: %v", err)
	}
	if errBody["error"] != "invalid_credentials" {
		t.Fatalf(`expected {"error":"invalid_credentials"} relayed unchanged, got %v`, errBody)
	}
	t.Logf("bad-credentials JSON login: HTTP %d, error=%q (relayed unchanged, no translation)", badResp.StatusCode, errBody["error"])
}

// TestLoginJSON_AuthorizeQueryPassthrough_ResolvesToRealAuthorizationCode is
// the cross-origin login continuity proof (CLOSEAUTH_CROSS_ORIGIN_LOGIN_
// DESIGN.md §3b): the new `authorizeQuery` JSON field, threaded through
// handleLoginJSON's form merge, into LoginController's full-fidelity
// parameter-map reconstruction, into LoginSuccessResponder's freshly-built
// /oauth2/authorize redirect.
//
// Deliberately does NOT carry the servlet-session cookie from step 1's
// unauthenticated /oauth2/authorize hit forward to step 2's POST
// /api/auth/login — a real cross-origin browser (BFF and backend on
// genuinely separate origins, no reverse proxy) never sends that cookie to
// the BFF's origin at all, so a test that DID carry it forward would (as
// TestLoginJSON_RedirectEnvelope_ResolvesToRealAuthorizationCode's baseline
// happens to, incidentally, via Spring Security's own RequestCacheAwareFilter
// request-wrapping) pass for the wrong reason — proving the OLD, cross-
// origin-broken session-resume mechanism still works when a session happens
// to be available, not proving THIS fix (the authorizeQuery field) is what
// makes the flow work when it genuinely isn't. Honestly simulating "no
// session at all" here is what makes this a real proof of the fix rather
// than a coincidence.
//
// Also proves the pass-through is genuinely NOT hand-enumerated on the Go
// side: the authorizeQuery carries an arbitrary OIDC extra (`nonce`) that
// appears nowhere in loginJSONRequest's named fields nor in
// handleLoginJSON's merge logic (which operates on the parsed map, not a
// field allowlist) — if a future edit accidentally reintroduced a hand-picked
// field list, this assertion would catch it.
func TestLoginJSON_AuthorizeQueryPassthrough_ResolvesToRealAuthorizationCode(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}
	const redirectURI = "http://localhost:23457/callback"
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, redirectURI)
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("login-json-authq-user")
	password := "Login-Json-AuthQ-Pw-123!"
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
		t.Fatalf("create user: %v", err)
	}

	// ---- step 1: unauthenticated /oauth2/authorize, DIRECT against the backend ----
	// Only used to obtain a REALISTIC original authorize query string (exactly
	// what LoginUrlAuthenticationEntryPoint would append to the redirect to
	// the BFF's login page) — its cookie jar (the servlet session) is
	// deliberately discarded below, never threaded into step 2.
	oauth := stack.OAuthClient(redirectURI)
	pkce, err := backend.NewPKCE()
	if err != nil {
		t.Fatalf("generate PKCE: %v", err)
	}
	state := "st-" + uuid.NewString()
	initResult, err := oauth.Authorize(ctx, creds.ClientID, "openid", pkce, state, backend.CookieJar{})
	if err != nil {
		t.Fatalf("initial authorize: %v", err)
	}
	if initResult.Outcome != backend.OutcomeLoginRequired {
		t.Fatalf("expected LOGIN_REQUIRED on the unauthenticated authorize hit, got %s (location=%q)",
			initResult.Outcome, initResult.Location)
	}
	loginPageURL, err := url.Parse(initResult.Location)
	if err != nil {
		t.Fatalf("parse entry-point redirect location: %v", err)
	}
	authorizeQuery := loginPageURL.RawQuery
	if authorizeQuery == "" {
		t.Fatalf("expected the entry-point redirect to carry the original /oauth2/authorize query string, got %q", initResult.Location)
	}
	// All seven core OAuth2/PKCE params should already be present from the
	// entry point's own redirect (this is the backend half's job, already
	// implemented/reviewed) — sanity-check before adding the arbitrary extra.
	for _, core := range []string{"client_id", "redirect_uri", "response_type", "scope", "state", "code_challenge", "code_challenge_method"} {
		if !strings.Contains(authorizeQuery, core+"=") {
			t.Fatalf("expected the captured authorizeQuery to already contain %q, got %q", core, authorizeQuery)
		}
	}
	// Append an arbitrary OIDC extra not in that core list, exactly as a
	// relying party's own /oauth2/authorize hit might have carried it
	// (LoginView.vue would have captured it too, since it captures the whole
	// query string verbatim).
	const nonceValue = "test-nonce-9f3c1a"
	authorizeQuery += "&nonce=" + url.QueryEscape(nonceValue)

	// ---- step 2: the BFF's real router, JSON login carrying authorizeQuery, NO servlet-session cookie ----
	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	loginBody, err := json.Marshal(map[string]any{
		"email":          email,
		"password":       password,
		"clientId":       creds.ClientID,
		"rememberMe":     false,
		"authorizeQuery": authorizeQuery,
	})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	loginReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/login", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatalf("build login request: %v", err)
	}
	loginReq.Header.Set("Content-Type", "application/json")
	// Deliberately NOT carrying initResult.Jar (the servlet session cookie)
	// here — see the function doc comment above.

	loginResp, err := http.DefaultClient.Do(loginReq)
	if err != nil {
		t.Fatalf("POST /api/auth/login: %v", err)
	}
	defer loginResp.Body.Close()

	if loginResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(loginResp.Body)
		t.Fatalf("expected 200 (translated JSON envelope) from the JSON login endpoint on valid credentials, got %d body=%s", loginResp.StatusCode, body)
	}
	var envelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(loginResp.Body).Decode(&envelope); err != nil {
		t.Fatalf("decode redirect envelope: %v", err)
	}
	if envelope.RedirectTo == "" {
		t.Fatalf("expected a non-empty redirectTo in the JSON envelope")
	}
	if !strings.Contains(envelope.RedirectTo, "/oauth2/authorize") {
		t.Fatalf("expected redirectTo to be a freshly reconstructed /oauth2/authorize request, got %q", envelope.RedirectTo)
	}
	redirectURL, err := url.Parse(envelope.RedirectTo)
	if err != nil {
		t.Fatalf("parse redirectTo: %v", err)
	}
	redirectParams := redirectURL.Query()
	for _, core := range []string{"client_id", "redirect_uri", "response_type", "scope", "state", "code_challenge", "code_challenge_method"} {
		if redirectParams.Get(core) == "" {
			t.Fatalf("expected redirectTo to carry %q reconstructed from authorizeQuery, got %q", core, envelope.RedirectTo)
		}
	}
	if got := redirectParams.Get("nonce"); got != nonceValue {
		t.Fatalf("expected the arbitrary extra param 'nonce' to survive end-to-end (not hand-enumerated), got %q, want %q in %q",
			got, nonceValue, envelope.RedirectTo)
	}
	if redirectParams.Get("client_id") != creds.ClientID {
		t.Fatalf("expected client_id in redirectTo to be %q (no duplicate/mangled value), got %q", creds.ClientID, redirectParams.Get("client_id"))
	}
	t.Logf("authorizeQuery pass-through envelope: redirectTo=%s (nonce survived: %s)", envelope.RedirectTo, redirectParams.Get("nonce"))

	var sessionCookie *http.Cookie
	for _, c := range loginResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected a CLOSEAUTH_SESSION cookie relayed from the backend's real Set-Cookie, got cookies: %v", loginResp.Cookies())
	}

	// ---- step 3: manually follow redirectTo, DIRECT against the backend, carrying ONLY the new session cookie ----
	// This is the genuinely cross-origin-honest resume: TenantSessionSsoFilter
	// recognizes CLOSEAUTH_SESSION (never JSESSIONID/HttpSessionRequestCache),
	// and that cookie is itself scoped to the backend's own origin — exactly
	// what a real browser would carry on this same-origin follow-up
	// navigation, with nothing from step 1 involved at all.
	noRedirectClient := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resumeReq, err := http.NewRequest(http.MethodGet, envelope.RedirectTo, nil)
	if err != nil {
		t.Fatalf("build resume request: %v", err)
	}
	resumeReq.AddCookie(sessionCookie)

	resumeResp, err := noRedirectClient.Do(resumeReq)
	if err != nil {
		t.Fatalf("follow redirectTo: %v", err)
	}
	defer resumeResp.Body.Close()

	if resumeResp.StatusCode != http.StatusFound || !strings.HasPrefix(resumeResp.Header.Get("Location"), redirectURI) {
		body, _ := io.ReadAll(resumeResp.Body)
		t.Fatalf("expected 302 to the client callback (%s) with a code, got HTTP %d location=%q body=%s",
			redirectURI, resumeResp.StatusCode, resumeResp.Header.Get("Location"), body)
	}
	callbackURL, err := url.Parse(resumeResp.Header.Get("Location"))
	if err != nil {
		t.Fatalf("parse callback location: %v", err)
	}
	if got := callbackURL.Query().Get("state"); got == "" {
		t.Fatalf("expected the callback to carry the original state param, got callback=%q", resumeResp.Header.Get("Location"))
	}
	code := callbackURL.Query().Get("code")
	if code == "" {
		t.Fatalf("no authorization code in callback %q", resumeResp.Header.Get("Location"))
	}
	t.Logf("redirectTo chain resolved (no servlet session carried at all): 302 to %s with a real authorization code (len=%d)", redirectURI, len(code))

	// ---- step 4: exchange the code — proves it's genuinely valid, not just present ----
	tokens, err := oauth.Exchange(ctx, creds.ClientID, creds.Secret, code, pkce.Verifier)
	if err != nil {
		t.Fatalf("exchange code for tokens: %v", err)
	}
	if tokens.AccessToken == "" {
		t.Fatalf("expected an access token from the code exchange")
	}
	t.Logf("code exchange ok (genuinely cross-origin, authorizeQuery-driven, arbitrary-extra-param-preserving): token_type=%s scope=%q", tokens.TokenType, tokens.Scope)
}
