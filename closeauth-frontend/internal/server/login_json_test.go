package server

import (
	"bytes"
	"context"
	"encoding/json"
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

	loginBody, err := json.Marshal(map[string]any{
		"email":      email,
		"password":   password,
		"clientId":   creds.ClientID,
		"rememberMe": false,
	})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	loginReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/login", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatalf("build login request: %v", err)
	}
	loginReq.Header.Set("Content-Type", "application/json")
	// Carry the servlet-session cookie from step 1 so the backend's
	// LoginSuccessResponder can find and resume the SAVED /oauth2/authorize
	// request (rather than falling back to the platform default URL).
	for name, value := range initResult.Jar {
		loginReq.AddCookie(&http.Cookie{Name: name, Value: value})
	}

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
		t.Fatalf("expected redirectTo to resume the SAVED /oauth2/authorize request, got %q "+
			"(a bare platform-default fallback here would mean the servlet-session cookie didn't carry through)", envelope.RedirectTo)
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
	// Carry BOTH the original servlet-session cookie (step 1) AND the new
	// CLOSEAUTH_SESSION cookie (step 2) — exactly the jar-threading discipline
	// internal/backend's own proven OAuthClient.Login() uses for its step
	// 2→3 handoff.
	for name, value := range initResult.Jar {
		resumeReq.AddCookie(&http.Cookie{Name: name, Value: value})
	}
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
