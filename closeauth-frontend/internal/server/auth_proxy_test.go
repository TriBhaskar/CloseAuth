package server

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestLoginLogoutProxy_RoundTrip is Deliverable 4's "prove it": a simulated
// request through the BFF's /login and /logout routes against the REAL
// running backend (via the Stage UI-1 harness) round-trips correctly — a
// valid login proxies through to a real Set-Cookie from the backend, relayed
// unchanged to the caller; an invalid login proxies through to the backend's
// real uniform failure response.
func TestLoginLogoutProxy_RoundTrip(t *testing.T) {
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
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, "http://localhost:12345/callback")
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("proxy-user")
	password := "Proxy-User-Pw-123!"
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
		t.Fatalf("create user: %v", err)
	}

	// Build the real Server (bypassing NewServer's env-var/logging setup —
	// this is a white-box test in package server, so it wires the one field
	// handleLoginProxy/handleLogoutProxy need directly) and expose its real
	// router through httptest, exactly as a browser would hit the BFF.
	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	noRedirectClient := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}

	// ---- valid login: real Set-Cookie from the backend, relayed unchanged ----
	loginForm := url.Values{"email": {email}, "password": {password}, "client_id": {creds.ClientID}}
	loginReq, err := http.NewRequest(http.MethodPost, ts.URL+"/login", strings.NewReader(loginForm.Encode()))
	if err != nil {
		t.Fatalf("build login request: %v", err)
	}
	loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	loginResp, err := noRedirectClient.Do(loginReq)
	if err != nil {
		t.Fatalf("proxy login request: %v", err)
	}
	defer loginResp.Body.Close()

	if loginResp.StatusCode != http.StatusFound {
		t.Fatalf("expected 302 from login proxy on valid credentials, got %d", loginResp.StatusCode)
	}
	var sessionCookie *http.Cookie
	for _, c := range loginResp.Cookies() {
		if c.Name == "CLOSEAUTH_SESSION" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected a CLOSEAUTH_SESSION cookie relayed from the backend's real Set-Cookie header, got cookies: %v", loginResp.Cookies())
	}
	if loginResp.Header.Get("Location") == "" {
		t.Fatalf("expected a Location header relayed from the backend, got none")
	}
	t.Logf("login proxy: HTTP %d, Location=%s, relayed cookie %s (len=%d)",
		loginResp.StatusCode, loginResp.Header.Get("Location"), sessionCookie.Name, len(sessionCookie.Value))

	// ---- logout: relays the backend's real response too ----
	logoutForm := url.Values{"client_id": {creds.ClientID}}
	logoutReq, err := http.NewRequest(http.MethodPost, ts.URL+"/logout", strings.NewReader(logoutForm.Encode()))
	if err != nil {
		t.Fatalf("build logout request: %v", err)
	}
	logoutReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	logoutReq.AddCookie(sessionCookie)

	logoutResp, err := noRedirectClient.Do(logoutReq)
	if err != nil {
		t.Fatalf("proxy logout request: %v", err)
	}
	defer logoutResp.Body.Close()
	if logoutResp.StatusCode != http.StatusNoContent && logoutResp.StatusCode != http.StatusFound {
		t.Fatalf("expected 204 or 302 from logout proxy, got %d", logoutResp.StatusCode)
	}
	t.Logf("logout proxy: HTTP %d", logoutResp.StatusCode)

	// ---- invalid login: the backend's real uniform failure response, relayed as-is ----
	badForm := url.Values{"email": {email}, "password": {"definitely-wrong"}, "client_id": {creds.ClientID}}
	badReq, err := http.NewRequest(http.MethodPost, ts.URL+"/login", strings.NewReader(badForm.Encode()))
	if err != nil {
		t.Fatalf("build bad-login request: %v", err)
	}
	badReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	badResp, err := noRedirectClient.Do(badReq)
	if err != nil {
		t.Fatalf("proxy bad-login request: %v", err)
	}
	defer badResp.Body.Close()
	if badResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 from login proxy on bad credentials (the backend's uniform enumeration-safe failure), got %d", badResp.StatusCode)
	}
	t.Logf("invalid login proxy: HTTP %d (uniform failure relayed as-is)", badResp.StatusCode)
}
