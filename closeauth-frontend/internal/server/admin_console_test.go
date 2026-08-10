package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"testing"
	"time"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/config"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// Stage UI-3a's "prove it": three real, Docker-gated round trips against the
// REAL backend (via testsupport.Stack) AND a REAL BFF (this package's own
// *Server, installed on the stack's held BFF listener via Stack.ServeBFF) —
// not assertions from reading code.
//
// # The cookie-port-blindness caveat (read before trusting these proofs too far)
//
// The harness's backend and BFF are both "localhost", differing only by
// port. Cookies have no port component (RFC 6265) — net/http/cookiejar (used
// here, exactly like a real browser) stores CLOSEAUTH_SESSION keyed by host
// "localhost" alone, so it is presented to BOTH origins automatically. On
// genuinely different hosts in production that would not hold; this is
// pre-existing UI-2 harness behavior (see stack.go's BFFBaseURL/startBFF
// doc comments), not something stage UI-3a introduced or needs to fix. None
// of the new admin-console code reads or forwards CLOSEAUTH_SESSION
// (grep-verifiable — it only ever reads/writes bff_admin_session,
// oauth_ctx_{slug}, and bff_admin_denied), so these proofs don't depend on
// the sharing to reach their conclusions — but the jar here is more
// permissive than a real cross-host browser would be, and a pass here
// doesn't by itself prove cross-host correctness.
//
// # Why these three, and not more
//
// These are exactly the three things the stage brief defines as "proven":
// (1) the full round trip through the real BFF ending in a correct session,
// (2) silent re-auth producing a fresh token with no login step, (3) a
// non-admin being refused without a session and without a loop. No CRUD is
// exercised (GET /v1/tenants/{tenantId}/admin-ping, the 7a demonstration
// endpoint, is the only backend call the session ever makes) — that's this
// stage's explicit non-goal.

// newAdminConsoleServer builds a *Server wired exactly like NewServer()
// would (see server.go), but pointed at stack's real backend and BFF
// listener, with an explicit reauthSkew — a real, production-meaningful
// BFFConfig field (BFF_REAUTH_SKEW), not a test-only hook. Setting it large
// is how TestAdminConsole_SilentReauthorization_RefreshesTokenWithoutLogin
// makes a freshly-minted 5-minute token read as "near expiry" without
// waiting 5 real minutes.
func newAdminConsoleServer(stack *testsupport.Stack, reauthSkew time.Duration) *Server {
	bffCfg := &config.BFFConfig{
		BaseURL:             stack.BFFBaseURL(),
		AdminCallbackPath:   "/admin/callback",
		AdminClientIDPrefix: "admin-console-",
		AdminScope:          "openid profile",
		SessionMaxAge:       12 * time.Hour,
		OAuthContextTTL:     10 * time.Minute,
		ReauthSkew:          reauthSkew,
		IsProduction:        false,
	}
	return &Server{
		authProxy:   proxy.New(stack.AppBaseURI() + stack.ContextPath()),
		bff:         bffCfg,
		oauthClient: backend.NewOAuthClient(stack.AppBaseURI(), stack.ContextPath(), bffCfg.AdminCallbackURL()),
		adminClient: backend.NewAdminClient(stack.AppBaseURI(), stack.ContextPath()),
	}
}

// newBrowserLikeClient is a real net/http.Client with a real
// net/http/cookiejar (RFC 6265 host+path matching, exactly like a browser)
// and manual redirect inspection — never auto-follow, mirroring every other
// flow-driving test in this repo (internal/backend's OAuthClient,
// closeauth-integration-tests' OAuthFlowClient): the whole point is
// observing WHICH redirect happened at each hop, not just where the chain
// eventually lands.
func newBrowserLikeClient(t *testing.T) *http.Client {
	t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("create cookie jar: %v", err)
	}
	return &http.Client{
		Jar:           jar,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// resolveLocation makes a (possibly relative) Location header value
// absolute against base — needed because handleAdminCallback's final
// redirect (http.Redirect(w, r, returnTo, ...)) writes a bare path, exactly
// as a browser's relative-Location handling expects, but this test's manual
// client.Get(...) calls need a full URL.
func resolveLocation(base, location string) string {
	if strings.HasPrefix(location, "http://") || strings.HasPrefix(location, "https://") {
		return location
	}
	return base + location
}

// driveAdminLoginToCallback drives steps 1-4 of the admin-console login
// round trip — GET .../admin/login -> (unauthenticated) backend
// /oauth2/authorize -> BFF's hosted login page -> POST /api/auth/login
// (exactly what LoginView.vue's fetch call sends — see
// login_json_test.go's TestLoginJSON_RedirectEnvelope_ResolvesToRealAuthorizationCode,
// which this mirrors) -> follow the JSON envelope's redirectTo, now
// SSO-recognized -> a 302 to /admin/callback with a code. Returns that final
// callback URL WITHOUT following it, so callers can inspect what happens
// next (a real admin succeeds; a non-admin is denied) themselves.
func driveAdminLoginToCallback(t *testing.T, client *http.Client, bffBase, slug, adminClientID, email, password, returnTo string) string {
	t.Helper()

	resp1, err := client.Get(bffBase + "/t/" + slug + "/admin/login?returnTo=" + url.QueryEscape(returnTo))
	if err != nil {
		t.Fatalf("GET admin/login: %v", err)
	}
	defer resp1.Body.Close()
	if resp1.StatusCode != http.StatusFound {
		t.Fatalf("admin/login: status = %d, want 302", resp1.StatusCode)
	}
	authorizeURL := resp1.Header.Get("Location")
	if !strings.Contains(authorizeURL, "/oauth2/authorize") {
		t.Fatalf("admin/login: Location = %q, want the backend's /oauth2/authorize", authorizeURL)
	}

	resp2, err := client.Get(authorizeURL)
	if err != nil {
		t.Fatalf("GET authorize (unauthenticated): %v", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusFound {
		t.Fatalf("authorize (unauthenticated): status = %d, want 302", resp2.StatusCode)
	}
	loginPageURL := resp2.Header.Get("Location")
	if !strings.Contains(loginPageURL, "/login") {
		t.Fatalf("authorize (unauthenticated): Location = %q, want the BFF login page", loginPageURL)
	}

	parsedLoginPage, err := url.Parse(loginPageURL)
	if err != nil {
		t.Fatalf("parse login page URL: %v", err)
	}

	loginBody, err := json.Marshal(map[string]any{
		"email":          email,
		"password":       password,
		"clientId":       adminClientID,
		"authorizeQuery": parsedLoginPage.RawQuery,
	})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	loginResp, err := client.Post(bffBase+"/api/auth/login", "application/json", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatalf("POST /api/auth/login: %v", err)
	}
	defer loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("POST /api/auth/login: status = %d, want 200", loginResp.StatusCode)
	}
	var envelope struct {
		RedirectTo string `json:"redirectTo"`
	}
	if err := json.NewDecoder(loginResp.Body).Decode(&envelope); err != nil {
		t.Fatalf("decode login envelope: %v", err)
	}
	if envelope.RedirectTo == "" {
		t.Fatalf("login envelope: empty redirectTo")
	}

	resp4, err := client.Get(envelope.RedirectTo)
	if err != nil {
		t.Fatalf("GET redirectTo (SSO-recognized authorize): %v", err)
	}
	defer resp4.Body.Close()
	if resp4.StatusCode != http.StatusFound {
		t.Fatalf("redirectTo: status = %d, want 302", resp4.StatusCode)
	}
	callbackURL := resp4.Header.Get("Location")
	if !strings.Contains(callbackURL, "/admin/callback") || !strings.Contains(callbackURL, "code=") {
		t.Fatalf("redirectTo: Location = %q, want /admin/callback carrying a code", callbackURL)
	}
	return callbackURL
}

// assertNoTokenLeak fails the test if body contains an access token or
// anything that looks like the start of a JWT — the "no token ever reaches
// browser JS" invariant, checked at the wire level.
func assertNoTokenLeak(t *testing.T, label, body string) {
	t.Helper()
	if strings.Contains(body, "access_token") {
		t.Errorf("%s: response body mentions access_token: %s", label, body)
	}
	if strings.Contains(body, "eyJ") {
		t.Errorf("%s: response body contains what looks like a JWT: %s", label, body)
	}
}

// assertNoTokenLeakExceptSecret is assertNoTokenLeak's UI-3c sibling for the
// two responses that carry a client secret ON PURPOSE (client create and
// secret regenerate) — the one deliberate, narrow exception to "no
// secret-shaped value ever reaches a response body". It strips ONLY the
// exact known secret string before delegating to the unmodified
// assertNoTokenLeak, so every OTHER field on that same response — and every
// other endpoint's response, which must call assertNoTokenLeak directly —
// stays fully checked. This does not weaken the general invariant; it scopes
// the one known, deliberate exception as narrowly as possible.
func assertNoTokenLeakExceptSecret(t *testing.T, label, body, knownSecret string) {
	t.Helper()
	if knownSecret == "" {
		t.Fatalf("%s: knownSecret must not be empty (would defeat the point of scoping the exception)", label)
	}
	stripped := strings.ReplaceAll(body, knownSecret, "")
	assertNoTokenLeak(t, label, stripped)
}

func TestAdminConsole_LoginRoundTrip_EstablishesTenantAdminSession(t *testing.T) {
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
	email := testsupport.Email("tenant-admin")
	password := "Tenant-Admin-Pw-123!"
	userID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password)
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	if err := fixtures.AssignTenantAdmin(ctx, platformToken, tenantID, userID); err != nil {
		t.Fatalf("assign TENANT_ADMIN: %v", err)
	}
	adminClientID := testsupport.AdminConsoleClientID(slug)

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()

	client := newBrowserLikeClient(t)
	returnTo := "/t/" + slug + "/console"

	callbackURL := driveAdminLoginToCallback(t, client, bffBase, slug, adminClientID, email, password, returnTo)

	resp5, err := client.Get(callbackURL)
	if err != nil {
		t.Fatalf("GET admin/callback: %v", err)
	}
	defer resp5.Body.Close()
	if resp5.StatusCode != http.StatusFound {
		t.Fatalf("admin/callback: status = %d, want 302", resp5.StatusCode)
	}

	var sessionCookie *http.Cookie
	for _, c := range resp5.Cookies() {
		if c.Name == "bff_admin_session" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("admin/callback: no bff_admin_session cookie in response, got: %v", resp5.Cookies())
	}
	if sessionCookie.Path != "/t/"+slug {
		t.Errorf("bff_admin_session Path = %q, want %q (per-tenant, path-scoped session)", sessionCookie.Path, "/t/"+slug)
	}

	landingURL := resolveLocation(bffBase, resp5.Header.Get("Location"))
	if landingURL != bffBase+returnTo {
		t.Errorf("admin/callback redirected to %q, want %q", landingURL, bffBase+returnTo)
	}
	landingResp, err := client.Get(landingURL)
	if err != nil {
		t.Fatalf("GET landing page: %v", err)
	}
	defer landingResp.Body.Close()
	if landingResp.StatusCode != http.StatusOK {
		t.Errorf("landing page: status = %d, want 200 (SPA index.html)", landingResp.StatusCode)
	}

	// ---- GET /t/{slug}/api/session: reflects the established TENANT_ADMIN session ----
	sessionResp, err := client.Get(bffBase + "/t/" + slug + "/api/session")
	if err != nil {
		t.Fatalf("GET api/session: %v", err)
	}
	defer sessionResp.Body.Close()
	sessionBody, _ := readBody(sessionResp.Body)
	if sessionResp.StatusCode != http.StatusOK {
		t.Fatalf("api/session: status = %d, want 200, body=%s", sessionResp.StatusCode, sessionBody)
	}
	var session struct {
		Authenticated bool     `json:"authenticated"`
		TenantID      string   `json:"tenantId"`
		Email         string   `json:"email"`
		TenantRoles   []string `json:"tenantRoles"`
	}
	if err := json.Unmarshal([]byte(sessionBody), &session); err != nil {
		t.Fatalf("decode api/session body: %v (body=%s)", err, sessionBody)
	}
	if !session.Authenticated {
		t.Errorf("api/session: authenticated = false, want true")
	}
	if session.TenantID != tenantID {
		t.Errorf("api/session: tenantId = %q, want %q", session.TenantID, tenantID)
	}
	if session.Email != email {
		t.Errorf("api/session: email = %q, want %q (the GET /v1/me backfill — email is not a JWT claim)", session.Email, email)
	}
	found := false
	for _, r := range session.TenantRoles {
		if r == "TENANT_ADMIN" {
			found = true
		}
	}
	if !found {
		t.Errorf("api/session: tenantRoles = %v, want to contain TENANT_ADMIN", session.TenantRoles)
	}
	assertNoTokenLeak(t, "api/session", sessionBody)

	// ---- GET /t/{slug}/api/ping: the real backend @RequiresTenantAccess gate accepting this session's token ----
	pingResp, err := client.Get(bffBase + "/t/" + slug + "/api/ping")
	if err != nil {
		t.Fatalf("GET api/ping: %v", err)
	}
	defer pingResp.Body.Close()
	pingBody, _ := readBody(pingResp.Body)
	if pingResp.StatusCode != http.StatusOK {
		t.Fatalf("api/ping: status = %d, want 200, body=%s", pingResp.StatusCode, pingBody)
	}
	var ping struct {
		OK       bool   `json:"ok"`
		TenantID string `json:"tenantId"`
	}
	if err := json.Unmarshal([]byte(pingBody), &ping); err != nil {
		t.Fatalf("decode api/ping body: %v (body=%s)", err, pingBody)
	}
	if !ping.OK || ping.TenantID != tenantID {
		t.Errorf("api/ping = %+v, want {ok:true tenantId:%s}", ping, tenantID)
	}
	assertNoTokenLeak(t, "api/ping", pingBody)

	t.Logf("round trip ok: tenant=%s slug=%s user=%s session established, ping confirmed", tenantID, slug, userID)
}

func TestAdminConsole_SilentReauthorization_RefreshesTokenWithoutLogin(t *testing.T) {
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
	email := testsupport.Email("reauth-admin")
	password := "Reauth-Admin-Pw-123!"
	userID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password)
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	if err := fixtures.AssignTenantAdmin(ctx, platformToken, tenantID, userID); err != nil {
		t.Fatalf("assign TENANT_ADMIN: %v", err)
	}
	adminClientID := testsupport.AdminConsoleClientID(slug)
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	returnTo := "/t/" + slug + "/console"

	// ---- establish a session with a deliberately large ReauthSkew, so the
	// freshly-minted 5-minute access token reads as "near expiry" immediately ----
	nearExpirySkew := 10 * time.Minute
	skewedServer := newAdminConsoleServer(stack, nearExpirySkew)
	stack.ServeBFF(t, skewedServer.RegisterRoutes())

	callbackURL := driveAdminLoginToCallback(t, client, bffBase, slug, adminClientID, email, password, returnTo)
	loginCallbackResp, err := client.Get(callbackURL)
	if err != nil {
		t.Fatalf("GET admin/callback (initial login): %v", err)
	}
	loginCallbackResp.Body.Close()
	if loginCallbackResp.StatusCode != http.StatusFound {
		t.Fatalf("admin/callback (initial login): status = %d, want 302", loginCallbackResp.StatusCode)
	}
	var firstSessionCookie *http.Cookie
	for _, c := range loginCallbackResp.Cookies() {
		if c.Name == "bff_admin_session" {
			firstSessionCookie = c
		}
	}
	if firstSessionCookie == nil {
		t.Fatalf("initial login: no bff_admin_session cookie set")
	}

	// ---- the near-expiry skew makes /api/ping demand reauth immediately ----
	pingResp1, err := client.Get(bffBase + "/t/" + slug + "/api/ping")
	if err != nil {
		t.Fatalf("GET api/ping (pre-reauth): %v", err)
	}
	pingBody1, _ := readBody(pingResp1.Body)
	pingResp1.Body.Close()
	if pingResp1.StatusCode != http.StatusUnauthorized {
		t.Fatalf("api/ping (pre-reauth): status = %d, want 401, body=%s", pingResp1.StatusCode, pingBody1)
	}
	var reauthError struct {
		Error      string `json:"error"`
		ReauthPath string `json:"reauthPath"`
	}
	if err := json.Unmarshal([]byte(pingBody1), &reauthError); err != nil {
		t.Fatalf("decode reauth error body: %v (body=%s)", err, pingBody1)
	}
	if reauthError.Error != "reauth_required" {
		t.Fatalf("api/ping (pre-reauth): error = %q, want reauth_required", reauthError.Error)
	}
	wantReauthPath := "/t/" + slug + "/admin/reauth"
	if reauthError.ReauthPath != wantReauthPath {
		t.Errorf("reauthPath = %q, want %q", reauthError.ReauthPath, wantReauthPath)
	}

	// ---- follow the reauth path — THE PROOF: this must reach a fresh code
	// via /oauth2/authorize with ZERO hops through /login ----
	resp1, err := client.Get(bffBase + reauthError.ReauthPath + "?returnTo=" + url.QueryEscape(returnTo))
	if err != nil {
		t.Fatalf("GET admin/reauth: %v", err)
	}
	defer resp1.Body.Close()
	if resp1.StatusCode != http.StatusFound {
		t.Fatalf("admin/reauth: status = %d, want 302", resp1.StatusCode)
	}
	authorizeURL := resp1.Header.Get("Location")
	if !strings.Contains(authorizeURL, "/oauth2/authorize") {
		t.Fatalf("admin/reauth: Location = %q, want the backend's /oauth2/authorize", authorizeURL)
	}

	resp2, err := client.Get(authorizeURL)
	if err != nil {
		t.Fatalf("GET authorize (reauth, SSO-recognized): %v", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusFound {
		t.Fatalf("authorize (reauth): status = %d, want 302", resp2.StatusCode)
	}
	reauthCallbackURL := resp2.Header.Get("Location")
	if strings.Contains(reauthCallbackURL, "/login") {
		t.Fatalf("authorize (reauth): Location = %q — silent reauth must NEVER touch /login; SSO should have recognized CLOSEAUTH_SESSION directly", reauthCallbackURL)
	}
	if !strings.Contains(reauthCallbackURL, "/admin/callback") || !strings.Contains(reauthCallbackURL, "code=") {
		t.Fatalf("authorize (reauth): Location = %q, want a direct /admin/callback with a fresh code (zero user interaction)", reauthCallbackURL)
	}

	resp3, err := client.Get(reauthCallbackURL)
	if err != nil {
		t.Fatalf("GET admin/callback (reauth): %v", err)
	}
	defer resp3.Body.Close()
	if resp3.StatusCode != http.StatusFound {
		t.Fatalf("admin/callback (reauth): status = %d, want 302", resp3.StatusCode)
	}
	var refreshedSessionCookie *http.Cookie
	for _, c := range resp3.Cookies() {
		if c.Name == "bff_admin_session" {
			refreshedSessionCookie = c
		}
	}
	if refreshedSessionCookie == nil {
		t.Fatalf("admin/callback (reauth): no re-issued bff_admin_session cookie")
	}
	if refreshedSessionCookie.Value == firstSessionCookie.Value {
		t.Errorf("admin/callback (reauth): bff_admin_session value is unchanged — expected a genuinely refreshed session")
	}

	// ---- with a normal skew, the freshly refreshed token genuinely works ----
	normalServer := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, normalServer.RegisterRoutes())

	pingResp2, err := client.Get(bffBase + "/t/" + slug + "/api/ping")
	if err != nil {
		t.Fatalf("GET api/ping (post-reauth): %v", err)
	}
	defer pingResp2.Body.Close()
	pingBody2, _ := readBody(pingResp2.Body)
	if pingResp2.StatusCode != http.StatusOK {
		t.Fatalf("api/ping (post-reauth): status = %d, want 200, body=%s", pingResp2.StatusCode, pingBody2)
	}
	t.Logf("silent reauth ok: fresh session established with zero /login hops, new token confirmed live")
}

func TestAdminConsole_NonAdminUser_IsRefusedWithoutSessionOrLoop(t *testing.T) {
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
	email := testsupport.Email("non-admin")
	password := "Non-Admin-Pw-123!"
	// Deliberately NO fixtures.AssignTenantAdmin call — this user is a
	// perfectly valid, ACTIVE tenant user who authenticates fine but holds
	// no TENANT_ADMIN role. Authorization ≠ authentication.
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
		t.Fatalf("create user: %v", err)
	}
	adminClientID := testsupport.AdminConsoleClientID(slug)

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()

	client := newBrowserLikeClient(t)
	returnTo := "/t/" + slug + "/console"

	callbackURL := driveAdminLoginToCallback(t, client, bffBase, slug, adminClientID, email, password, returnTo)

	resp5, err := client.Get(callbackURL)
	if err != nil {
		t.Fatalf("GET admin/callback: %v", err)
	}
	defer resp5.Body.Close()
	if resp5.StatusCode != http.StatusFound {
		t.Fatalf("admin/callback: status = %d, want 302", resp5.StatusCode)
	}
	deniedLocation := resp5.Header.Get("Location")
	if !strings.Contains(deniedLocation, "/t/"+slug+"/denied") || !strings.Contains(deniedLocation, "reason=not_tenant_admin") {
		t.Fatalf("admin/callback: Location = %q, want /t/%s/denied?reason=not_tenant_admin", deniedLocation, slug)
	}
	for _, c := range resp5.Cookies() {
		if c.Name == "bff_admin_session" {
			t.Fatalf("admin/callback: a bff_admin_session cookie was set for a non-admin user — must never happen")
		}
	}

	// ---- GET /t/{slug}/api/session reflects the denial, no session ----
	sessionResp, err := client.Get(bffBase + "/t/" + slug + "/api/session")
	if err != nil {
		t.Fatalf("GET api/session: %v", err)
	}
	defer sessionResp.Body.Close()
	sessionBody, _ := readBody(sessionResp.Body)
	var session struct {
		Authenticated bool   `json:"authenticated"`
		Denied        bool   `json:"denied"`
		DeniedReason  string `json:"deniedReason"`
	}
	if err := json.Unmarshal([]byte(sessionBody), &session); err != nil {
		t.Fatalf("decode api/session body: %v (body=%s)", err, sessionBody)
	}
	if session.Authenticated {
		t.Errorf("api/session: authenticated = true, want false (not a TENANT_ADMIN)")
	}
	if !session.Denied || session.DeniedReason != "not_tenant_admin" {
		t.Errorf("api/session: denied=%v reason=%q, want denied=true reason=not_tenant_admin", session.Denied, session.DeniedReason)
	}

	// ---- LOOP PROOF: a second admin/login attempt must short-circuit
	// straight to /denied, with ZERO hops through /oauth2/authorize ----
	resp6, err := client.Get(bffBase + "/t/" + slug + "/admin/login?returnTo=" + url.QueryEscape(returnTo))
	if err != nil {
		t.Fatalf("GET admin/login (repeat visit): %v", err)
	}
	defer resp6.Body.Close()
	if resp6.StatusCode != http.StatusFound {
		t.Fatalf("admin/login (repeat visit): status = %d, want 302", resp6.StatusCode)
	}
	repeatLocation := resp6.Header.Get("Location")
	if strings.Contains(repeatLocation, "/oauth2/authorize") {
		t.Fatalf("admin/login (repeat visit): Location = %q — must short-circuit to /denied without touching /oauth2/authorize (loop break)", repeatLocation)
	}
	if !strings.Contains(repeatLocation, "/t/"+slug+"/denied") {
		t.Fatalf("admin/login (repeat visit): Location = %q, want /t/%s/denied", repeatLocation, slug)
	}

	// ---- ESCAPE PROOF: dismissing the denial marker restores the ability to retry ----
	csrfResp, err := client.Get(bffBase + "/api/csrf")
	if err != nil {
		t.Fatalf("GET api/csrf: %v", err)
	}
	defer csrfResp.Body.Close()
	var csrf struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(csrfResp.Body).Decode(&csrf); err != nil {
		t.Fatalf("decode csrf token: %v", err)
	}

	dismissReq, err := http.NewRequest(http.MethodPost, bffBase+"/t/"+slug+"/api/denied/dismiss", nil)
	if err != nil {
		t.Fatalf("build dismiss request: %v", err)
	}
	dismissReq.Header.Set("X-CSRF-Token", csrf.Token)
	dismissResp, err := client.Do(dismissReq)
	if err != nil {
		t.Fatalf("POST denied/dismiss: %v", err)
	}
	defer dismissResp.Body.Close()
	if dismissResp.StatusCode != http.StatusNoContent {
		t.Fatalf("POST denied/dismiss: status = %d, want 204", dismissResp.StatusCode)
	}

	resp7, err := client.Get(bffBase + "/t/" + slug + "/admin/login?returnTo=" + url.QueryEscape(returnTo))
	if err != nil {
		t.Fatalf("GET admin/login (after dismiss): %v", err)
	}
	defer resp7.Body.Close()
	if resp7.StatusCode != http.StatusFound {
		t.Fatalf("admin/login (after dismiss): status = %d, want 302", resp7.StatusCode)
	}
	retryLocation := resp7.Header.Get("Location")
	if !strings.Contains(retryLocation, "/oauth2/authorize") {
		t.Fatalf("admin/login (after dismiss): Location = %q, want /oauth2/authorize — dismiss should have cleared the denial marker", retryLocation)
	}

	t.Logf("non-admin refusal ok: no session ever created, repeat visits short-circuit without looping, dismiss restores retry (tenant=%s)", tenantID)
}

// readBody is a tiny io.ReadAll wrapper returning a string, to keep the test
// bodies above focused on assertions rather than byte-slice plumbing.
func readBody(r io.Reader) (string, error) {
	b, err := io.ReadAll(r)
	return string(b), err
}
