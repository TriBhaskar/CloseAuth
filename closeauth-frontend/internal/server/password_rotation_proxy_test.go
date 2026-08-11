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
	"time"

	"github.com/google/uuid"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestPasswordRotationProxy_TempPasswordOnRamp_ResolvesToRealAuthorizationCode
// is Phase 4a's required proof for the temp-password on-ramp, mirroring
// login_json_test.go's rigor (that file's own doc comment explains why: a
// backend 302 → JSON-envelope translation is only proven correct if the
// envelope's URL is actually followable, not merely present):
//
//  1. Drive a real, unauthenticated GET /oauth2/authorize directly at the
//     backend for a freshly registered confidential client — exactly the
//     "interrupted login" this whole feature exists to resume.
//  2. POST the JSON login endpoint through the BFF's real router with the
//     bootstrapped user's TEMPORARY password and the captured
//     authorizeQuery — LoginPolicyService.authenticate returns
//     ROTATION_REQUIRED, so this must translate to a redirect envelope
//     pointing at /password-rotation, NOT establish a session (no
//     CLOSEAUTH_SESSION cookie may be present here — see
//     LoginController's own javadoc: "identity proven, access withheld").
//  3. POST the new JSON password-rotation-confirm endpoint through the SAME
//     router with the extracted token/client_id/authorize_query — this is
//     the highest-risk hop in the phase (handlers_password_rotation_proxy.go's
//     doc comment): if authorize_query were ever exploded into separate
//     form keys instead of forwarded as one opaque field, the backend would
//     silently ignore it and this test's Location assertion below would
//     catch it immediately (a bare BFF-root redirect instead of a real
//     /oauth2/authorize resume, and a missing `state`).
//  4. Follow the resume URL directly against the backend, carrying ONLY the
//     CLOSEAUTH_SESSION cookie the confirm call relayed — proving the
//     session it established is genuinely usable, not merely present.
//  5. Exchange the resulting code for tokens — proves it's genuinely valid.
func TestPasswordRotationProxy_TempPasswordOnRamp_ResolvesToRealAuthorizationCode(t *testing.T) {
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
	const redirectURI = "http://localhost:34567/callback"
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, redirectURI)
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("rotation-confirm-user")
	bootstrap, err := fixtures.BootstrapTenantAdmin(ctx, platformToken, tenantID, email)
	if err != nil {
		t.Fatalf("bootstrap tenant admin: %v", err)
	}
	const newPassword = "Brand-New-Real-Pw-2!"

	oauth := stack.OAuthClient(redirectURI)
	pkce, err := backend.NewPKCE()
	if err != nil {
		t.Fatalf("generate PKCE: %v", err)
	}
	state := "st-" + uuid.NewString()

	// ---- step 1: unauthenticated /oauth2/authorize, DIRECT against the backend ----
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

	// ---- step 2: the BFF's real router, JSON login with the TEMP password ----
	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	loginBody, err := json.Marshal(map[string]any{
		"email":          email,
		"password":       bootstrap.TemporaryPassword,
		"clientId":       creds.ClientID,
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
	loginResp, err := http.DefaultClient.Do(loginReq)
	if err != nil {
		t.Fatalf("POST /api/auth/login: %v", err)
	}
	defer loginResp.Body.Close()

	if loginResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(loginResp.Body)
		t.Fatalf("expected 200 (translated JSON envelope) from a rotation-required login, got %d body=%s",
			loginResp.StatusCode, body)
	}
	for _, c := range loginResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			t.Fatalf("a rotation-required login must never set CLOSEAUTH_SESSION — identity proven, access withheld")
		}
	}
	var loginEnvelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(loginResp.Body).Decode(&loginEnvelope); err != nil {
		t.Fatalf("decode login redirect envelope: %v", err)
	}
	if !strings.Contains(loginEnvelope.RedirectTo, "/password-rotation") {
		t.Fatalf("expected the rotation-required login's redirectTo to point at /password-rotation, got %q",
			loginEnvelope.RedirectTo)
	}
	rotationURL, err := url.Parse(loginEnvelope.RedirectTo)
	if err != nil {
		t.Fatalf("parse rotation redirectTo: %v", err)
	}
	rotationParams := rotationURL.Query()
	token := rotationParams.Get("token")
	rotationClientID := rotationParams.Get("client_id")
	rotationAuthorizeQuery := rotationParams.Get("authorize_query")
	if token == "" {
		t.Fatalf("expected a non-empty token in the rotation redirect, got %q", loginEnvelope.RedirectTo)
	}
	if rotationAuthorizeQuery == "" {
		t.Fatalf("expected the rotation redirect to carry authorize_query (the resume context), got %q",
			loginEnvelope.RedirectTo)
	}
	t.Logf("rotation-required login: redirectTo=%s", loginEnvelope.RedirectTo)

	// ---- step 3: the BFF's real router, JSON password-rotation confirm ----
	// rotationAuthorizeQuery here is EXACTLY what PasswordRotationView.vue
	// would have read via new URLSearchParams(window.location.search).get(
	// 'authorize_query') — url.Query() already decoded it once, matching
	// that component's own single-decode contract (see its header comment).
	confirmBody, err := json.Marshal(map[string]any{
		"token":          token,
		"password":       newPassword,
		"clientId":       rotationClientID,
		"authorizeQuery": rotationAuthorizeQuery,
	})
	if err != nil {
		t.Fatalf("marshal confirm body: %v", err)
	}
	confirmReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/password-rotation/confirm", bytes.NewReader(confirmBody))
	if err != nil {
		t.Fatalf("build confirm request: %v", err)
	}
	confirmReq.Header.Set("Content-Type", "application/json")
	confirmResp, err := http.DefaultClient.Do(confirmReq)
	if err != nil {
		t.Fatalf("POST /api/auth/password-rotation/confirm: %v", err)
	}
	defer confirmResp.Body.Close()

	if confirmResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(confirmResp.Body)
		t.Fatalf("expected 200 (translated JSON envelope) confirming rotation with a valid token, got %d body=%s",
			confirmResp.StatusCode, body)
	}
	var sessionCookie *http.Cookie
	for _, c := range confirmResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected confirm to relay a CLOSEAUTH_SESSION cookie — the first session in this whole flow")
	}
	var confirmEnvelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(confirmResp.Body).Decode(&confirmEnvelope); err != nil {
		t.Fatalf("decode confirm redirect envelope: %v", err)
	}
	if !strings.Contains(confirmEnvelope.RedirectTo, "/oauth2/authorize") {
		t.Fatalf("expected the resume target to be a freshly reconstructed /oauth2/authorize request — got %q "+
			"(a bare BFF-root fallback here would mean authorize_query was silently dropped on the way to the "+
			"backend, e.g. by mistakenly exploding it into separate form keys)", confirmEnvelope.RedirectTo)
	}
	resumeParams, err := url.Parse(confirmEnvelope.RedirectTo)
	if err != nil {
		t.Fatalf("parse resume URL: %v", err)
	}
	if got := resumeParams.Query().Get("state"); got != state {
		t.Fatalf("expected the original state %q to survive the round trip, got %q in %q", state, got, confirmEnvelope.RedirectTo)
	}
	t.Logf("password-rotation confirm: redirectTo=%s, relayed cookie %s (len=%d)",
		confirmEnvelope.RedirectTo, sessionCookie.Name, len(sessionCookie.Value))

	// ---- step 4: manually follow the resume URL, DIRECT against the backend ----
	noRedirectClient := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resumeReq, err := http.NewRequest(http.MethodGet, confirmEnvelope.RedirectTo, nil)
	if err != nil {
		t.Fatalf("build resume request: %v", err)
	}
	resumeReq.AddCookie(sessionCookie)
	resumeResp, err := noRedirectClient.Do(resumeReq)
	if err != nil {
		t.Fatalf("follow resume URL: %v", err)
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
	code := callbackURL.Query().Get("code")
	if code == "" {
		t.Fatalf("no authorization code in callback %q", resumeResp.Header.Get("Location"))
	}

	// ---- step 5: exchange the code — proves it's genuinely valid ----
	tokens, err := oauth.Exchange(ctx, creds.ClientID, creds.Secret, code, pkce.Verifier)
	if err != nil {
		t.Fatalf("exchange code for tokens: %v", err)
	}
	if tokens.AccessToken == "" {
		t.Fatalf("expected an access token from the code exchange")
	}
	t.Logf("rotation resume resolved end to end: token_type=%s scope=%q", tokens.TokenType, tokens.Scope)

	// ---- failure case: a consumed token confirms generically, no enumeration ----
	reuseBody, err := json.Marshal(map[string]any{
		"token":    token,
		"password": "Another-Pw-789!",
		"clientId": rotationClientID,
	})
	if err != nil {
		t.Fatalf("marshal reuse body: %v", err)
	}
	reuseReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/password-rotation/confirm", bytes.NewReader(reuseBody))
	if err != nil {
		t.Fatalf("build reuse request: %v", err)
	}
	reuseReq.Header.Set("Content-Type", "application/json")
	reuseResp, err := http.DefaultClient.Do(reuseReq)
	if err != nil {
		t.Fatalf("POST /api/auth/password-rotation/confirm (reused token): %v", err)
	}
	defer reuseResp.Body.Close()
	if reuseResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected a generic 400 reusing an already-consumed token, got %d", reuseResp.StatusCode)
	}
	for _, c := range reuseResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			t.Fatalf("a rejected confirm must never set CLOSEAUTH_SESSION")
		}
	}
	t.Logf("reused-token confirm: HTTP %d (generic, enumeration-safe rejection)", reuseResp.StatusCode)
}

// TestPasswordRotationProxy_EmailedLinkOnRamp_EstablishesSessionWithBareRedirect
// covers the second on-ramp: TenantOnboardingService.bootstrapFirstAdmin's
// own onboarding email, which carries NO authorize_query (there was no
// interrupted /oauth2/authorize hit to resume — the user is bootstrapping
// straight from their inbox). Confirms Part 3 point 5 of
// docs/TENANT_ONBOARDING_UI_ANALYSIS.md: the shipped behavior lands the user
// on the bare BFF base URL, ALREADY signed in — one hop shorter than that
// doc's original §2.2.2 design (an extra /login?client_id=... hop), because
// PasswordRotationController.confirm establishes the session in the same
// call that sets the password.
func TestPasswordRotationProxy_EmailedLinkOnRamp_EstablishesSessionWithBareRedirect(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, slug, err := fixtures.ProvisionActiveTenantWithSlug(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}
	email := testsupport.Email("rotation-emailed-user")
	if _, err := fixtures.BootstrapTenantAdmin(ctx, platformToken, tenantID, email); err != nil {
		t.Fatalf("bootstrap tenant admin: %v", err)
	}

	mailpit, err := stack.Mailpit(ctx)
	if err != nil {
		t.Fatalf("build mailpit client: %v", err)
	}
	message, err := mailpit.WaitForMessageTo(ctx, email, 30*time.Second)
	if err != nil {
		t.Fatalf("wait for onboarding email: %v", err)
	}
	token, err := testsupport.ExtractLinkParam(message.Text, "token")
	if err != nil {
		t.Fatalf("extract onboarding token: %v", err)
	}
	clientID := testsupport.AdminConsoleClientID(slug)

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	confirmBody, err := json.Marshal(map[string]any{
		"token":    token,
		"password": "Emailed-Onramp-Pw-3!",
		"clientId": clientID,
		// No authorizeQuery — the emailed on-ramp genuinely has none.
	})
	if err != nil {
		t.Fatalf("marshal confirm body: %v", err)
	}
	confirmReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/password-rotation/confirm", bytes.NewReader(confirmBody))
	if err != nil {
		t.Fatalf("build confirm request: %v", err)
	}
	confirmReq.Header.Set("Content-Type", "application/json")
	confirmResp, err := http.DefaultClient.Do(confirmReq)
	if err != nil {
		t.Fatalf("POST /api/auth/password-rotation/confirm: %v", err)
	}
	defer confirmResp.Body.Close()

	if confirmResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(confirmResp.Body)
		t.Fatalf("expected 200 confirming the emailed-link on-ramp, got %d body=%s", confirmResp.StatusCode, body)
	}
	var sessionCookie *http.Cookie
	for _, c := range confirmResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil || sessionCookie.Value == "" {
		t.Fatalf("expected the emailed-link on-ramp's confirm to establish a real session, got cookies: %v",
			confirmResp.Cookies())
	}
	var envelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(confirmResp.Body).Decode(&envelope); err != nil {
		t.Fatalf("decode confirm redirect envelope: %v", err)
	}
	if strings.Contains(envelope.RedirectTo, "/oauth2/authorize") || strings.Contains(envelope.RedirectTo, "/login") {
		t.Fatalf("expected the emailed on-ramp's redirectTo to be the bare BFF base URL (already signed in, "+
			"one hop shorter than an extra login — Part 3 point 5), got %q", envelope.RedirectTo)
	}
	if envelope.RedirectTo != stack.BFFBaseURL() {
		t.Fatalf("expected redirectTo to equal the configured bff.base-url exactly, got %q want %q",
			envelope.RedirectTo, stack.BFFBaseURL())
	}
	t.Logf("emailed-link on-ramp confirm: redirectTo=%s (bare, already signed in)", envelope.RedirectTo)
}

// TestPasswordRotationProxy_InvalidToken_RelaysGenericBadRequest proves the
// proxy relays the backend's uniform, non-enumerating 400 unchanged (no
// translation attempted, no cookie leaked) for a token that was never
// issued — the case a user hits by mistyping or reusing a stale bookmarked
// link.
func TestPasswordRotationProxy_InvalidToken_RelaysGenericBadRequest(t *testing.T) {
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
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, "http://localhost:34568/callback")
	if err != nil {
		t.Fatalf("register client: %v", err)
	}

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	confirmBody, err := json.Marshal(map[string]any{
		"token":    "definitely-not-a-real-token",
		"password": "Whatever-Pw-1!",
		"clientId": creds.ClientID,
	})
	if err != nil {
		t.Fatalf("marshal confirm body: %v", err)
	}
	confirmReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/password-rotation/confirm", bytes.NewReader(confirmBody))
	if err != nil {
		t.Fatalf("build confirm request: %v", err)
	}
	confirmReq.Header.Set("Content-Type", "application/json")
	confirmResp, err := http.DefaultClient.Do(confirmReq)
	if err != nil {
		t.Fatalf("POST /api/auth/password-rotation/confirm: %v", err)
	}
	defer confirmResp.Body.Close()

	if confirmResp.StatusCode != http.StatusBadRequest {
		body, _ := io.ReadAll(confirmResp.Body)
		t.Fatalf("expected a generic 400 for an invalid token, got %d body=%s", confirmResp.StatusCode, body)
	}
	for _, c := range confirmResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			t.Fatalf("a rejected confirm must never set CLOSEAUTH_SESSION")
		}
	}
}

// TestPasswordRotationProxy_MissingFields_RejectsBeforeCallingTheBackend
// proves the BFF itself validates required fields (token/password/clientId)
// before ever relaying to the backend — the same discipline handleLoginJSON
// applies to email/password.
func TestPasswordRotationProxy_MissingFields_RejectsBeforeCallingTheBackend(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://127.0.0.1:1")} // unreachable — proves the backend is never called
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	cases := []map[string]any{
		{"password": "x", "clientId": "c1"},
		{"token": "t1", "clientId": "c1"},
		{"token": "t1", "password": "x"},
	}
	for _, body := range cases {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal body: %v", err)
		}
		req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/password-rotation/confirm", bytes.NewReader(encoded))
		if err != nil {
			t.Fatalf("build request: %v", err)
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("POST /api/auth/password-rotation/confirm: %v", err)
		}
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("body %v: expected 400 without ever reaching the (unreachable) backend, got %d", body, resp.StatusCode)
		}
		resp.Body.Close()
	}
}
