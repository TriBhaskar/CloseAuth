package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestConsentContextProxy_RoundTrip is Stage UI-2c-ii, Deliverable 1's "prove
// it": drives a REAL pending consent-required flow through the real
// Testcontainers harness (a disposable tenant + a resource server with one
// auto-grantable and one consent-requiring scope + a non-trusted client + an
// active user; authorize → login → resume, landing on the backend's real
// consent-required redirect — mirroring closeauth-backend's own
// ConsentBrandingIntegrationTest.java flow), then calls the NEW BFF proxy
// route with the exact client_id/scope/state query the real redirect carried
// and confirms it relays a real ConsentContext correctly, WITH the injected
// authorizeUrl field present and pointing at the REAL backend origin this
// harness is using — never the BFF's own httptest origin.
//
// Setup (resource server / scope / non-trusted client) goes straight through
// the real backend's admin API — not the BFF — and the authorize/login/resume
// legs go straight against the real backend too (same discipline
// auth_proxy_test.go uses for its own setup): the point of THIS test is the
// consent proxy route, not re-proving login or the consent-required
// classification, both already proven elsewhere.
func TestConsentContextProxy_RoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	ctx := context.Background()
	fixtures := testsupport.NewFixtures(stack)
	admin := stack.AdminClient()

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}

	// A resource server with "read" (requiresConsent=false, auto-grantable)
	// and "write" (requiresConsent=true) — mirrors
	// ConsentBrandingIntegrationTest.java's setupResourceServer, driven
	// through the admin REST API (TenantResourceServerController) since the
	// Go harness has no direct service access.
	rsSlug := "consentapi-" + testsupport.Suffix()
	rsResp, err := admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/resource-servers", map[string]any{
		"slug":               rsSlug,
		"name":               "Consent Test API",
		"audienceIdentifier": "https://" + rsSlug + ".example/api",
	})
	if err != nil {
		t.Fatalf("create resource server: %v", err)
	}
	if rsResp.StatusCode != http.StatusCreated {
		t.Fatalf("create resource server: expected 201, got %d body=%s", rsResp.StatusCode, rsResp.Body)
	}
	var rs struct {
		ID string `json:"id"`
	}
	if err := rsResp.JSON(&rs); err != nil {
		t.Fatalf("decode resource server response: %v", err)
	}

	addScope := func(name, description string, isDefault, requiresConsent bool) {
		t.Helper()
		resp, err := admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/resource-servers/"+rs.ID+"/scopes", map[string]any{
			"scopeName":       name,
			"description":     description,
			"isDefault":       isDefault,
			"requiresConsent": requiresConsent,
		})
		if err != nil {
			t.Fatalf("add scope %s: %v", name, err)
		}
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("add scope %s: expected 201, got %d body=%s", name, resp.StatusCode, resp.Body)
		}
	}
	addScope("read", "Read consent-test items", true, false)
	addScope("write", "Modify consent-test items", false, true)

	readScope := rsSlug + ":read"
	writeScope := rsSlug + ":write"

	// A NON-TRUSTED client — the one thing that makes consent required at all
	// (RegisterClientCommand.trusted's javadoc: false => consent required).
	clientID := testsupport.ClientID("consent")
	secret := "secret-" + testsupport.Suffix()
	const redirectURI = "http://127.0.0.1/callback"
	clientResp, err := admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/clients", map[string]any{
		"clientId":        clientID,
		"clientName":      clientID,
		"clientSecret":    secret,
		"grantTypes":      []string{"authorization_code", "refresh_token"},
		"scopes":          []string{"openid", readScope, writeScope},
		"redirectUris":    []string{redirectURI},
		"requireProofKey": true,
		"trusted":         false,
	})
	if err != nil {
		t.Fatalf("register non-trusted client: %v", err)
	}
	if clientResp.StatusCode != http.StatusCreated {
		t.Fatalf("register non-trusted client: expected 201, got %d body=%s", clientResp.StatusCode, clientResp.Body)
	}

	email := testsupport.Email("consent")
	const password = "Consent-User-Pw-123!"
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
		t.Fatalf("create user: %v", err)
	}

	// ---- drive a REAL authorize -> login -> resume flow directly against
	// the backend (setup, not the thing under test) until it lands on the
	// real consent-required redirect. ----
	pkce, err := backend.NewPKCE()
	if err != nil {
		t.Fatalf("generate pkce: %v", err)
	}
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("build cookie jar: %v", err)
	}
	browserlikeClient := &http.Client{
		Jar: jar,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	backendBase := stack.AppBaseURI() + stack.ContextPath()
	state := "st-" + testsupport.Suffix()
	scope := "openid " + readScope + " " + writeScope

	authorizeQuery := url.Values{
		"response_type":         {"code"},
		"client_id":             {clientID},
		"redirect_uri":          {redirectURI},
		"scope":                 {scope},
		"state":                 {state},
		"code_challenge":        {pkce.Challenge},
		"code_challenge_method": {"S256"},
	}
	initResp, err := browserlikeClient.Get(backendBase + "/oauth2/authorize?" + authorizeQuery.Encode())
	if err != nil {
		t.Fatalf("initial authorize: %v", err)
	}
	initResp.Body.Close()
	if initResp.StatusCode != http.StatusFound {
		t.Fatalf("expected 302 (login required) from the initial authorize, got %d", initResp.StatusCode)
	}

	loginForm := url.Values{
		"response_type":         {"code"},
		"client_id":             {clientID},
		"redirect_uri":          {redirectURI},
		"scope":                 {scope},
		"state":                 {state},
		"code_challenge":        {pkce.Challenge},
		"code_challenge_method": {"S256"},
		"email":                 {email},
		"password":              {password},
	}
	loginResp, err := browserlikeClient.PostForm(backendBase+"/login", loginForm)
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusFound {
		t.Fatalf("expected 302 from a valid login, got %d", loginResp.StatusCode)
	}
	resumeLocation := loginResp.Header.Get("Location")
	if resumeLocation == "" {
		t.Fatalf("expected a Location header from login, got none")
	}

	resumeResp, err := browserlikeClient.Get(resumeLocation)
	if err != nil {
		t.Fatalf("resume authorize: %v", err)
	}
	resumeResp.Body.Close()
	if resumeResp.StatusCode != http.StatusFound {
		t.Fatalf("expected 302 from the resumed authorize, got %d", resumeResp.StatusCode)
	}
	consentRedirect := resumeResp.Header.Get("Location")
	parsedConsentRedirect, err := url.Parse(consentRedirect)
	if err != nil {
		t.Fatalf("parse consent redirect: %v", err)
	}
	if !strings.Contains(parsedConsentRedirect.Path, "consent") {
		t.Fatalf("expected a consent-required redirect (path containing \"consent\"), got Location=%q", consentRedirect)
	}
	consentQuery := parsedConsentRedirect.RawQuery
	if consentQuery == "" {
		t.Fatalf("expected the consent redirect to carry client_id/scope/state (appended by SAS itself), got none: %q", consentRedirect)
	}
	t.Logf("real consent-required redirect from the backend: %s", consentRedirect)

	// SAS's consent-page "state" is a FRESH, securely-random CSRF token it
	// generates for the consent form specifically — NOT an echo of the
	// original OAuth2 `state` this test's own authorize/login calls used
	// (confirmed here: they differ). This mirrors
	// ConsentBrandingIntegrationTest.java's own approach exactly — it reads
	// `state` back off consentUrl (`queryParam(consentUrl, "state")`) rather
	// than reusing its own `state1` variable — so the assertion below checks
	// self-consistency (the proxy echoes exactly what the real redirect
	// carried), not equality with this test's originally-chosen state.
	wantState := parsedConsentRedirect.Query().Get("state")
	if wantState == "" {
		t.Fatalf("expected the consent redirect to carry a state param, got none: %q", consentRedirect)
	}

	// ---- NOW exercise the thing actually under test: the BFF's own GET
	// /oauth2/consent proxy route, hit with the SAME query the real redirect
	// carried. ----
	s := &Server{
		authProxy:    proxy.New(backendBase),
		authorizeURL: backendBase + "/oauth2/authorize",
	}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	proxyResp, err := http.Get(ts.URL + "/oauth2/consent?" + consentQuery)
	if err != nil {
		t.Fatalf("GET /oauth2/consent (proxy): %v", err)
	}
	defer proxyResp.Body.Close()
	body, err := io.ReadAll(proxyResp.Body)
	if err != nil {
		t.Fatalf("read consent proxy body: %v", err)
	}
	if proxyResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 from the consent proxy, got %d body=%s", proxyResp.StatusCode, body)
	}

	var got struct {
		ClientID   string `json:"clientId"`
		ClientName string `json:"clientName"`
		State      string `json:"state"`
		Scopes     []struct {
			Scope           string `json:"scope"`
			Description     string `json:"description"`
			RequiresConsent bool   `json:"requiresConsent"`
		} `json:"scopes"`
		AlreadyGranted []string `json:"alreadyGranted"`
		AuthorizeURL   string   `json:"authorizeUrl"`
	}
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("decode consent context: %v (body=%s)", err, body)
	}
	t.Logf("consent context via BFF proxy: %+v", got)

	if got.ClientID != clientID {
		t.Errorf("expected clientId=%s, got %s", clientID, got.ClientID)
	}
	if got.State != wantState {
		t.Errorf("expected state=%s, got %s", wantState, got.State)
	}

	// THE point of Deliverable 1: the proxy injects authorizeUrl pointing at
	// the REAL backend's own /oauth2/authorize — never the BFF's own origin,
	// never empty, never a relative path.
	wantAuthorizeURL := backendBase + "/oauth2/authorize"
	if got.AuthorizeURL != wantAuthorizeURL {
		t.Fatalf("expected authorizeUrl=%q (the real backend origin), got %q", wantAuthorizeURL, got.AuthorizeURL)
	}
	if strings.Contains(got.AuthorizeURL, ts.URL) {
		t.Fatalf("authorizeUrl must NOT point at the BFF's own origin (%s), got %q", ts.URL, got.AuthorizeURL)
	}

	byScope := map[string]bool{}
	scopePresent := map[string]bool{}
	for _, sc := range got.Scopes {
		byScope[sc.Scope] = sc.RequiresConsent
		scopePresent[sc.Scope] = true
	}
	if !scopePresent[readScope] || byScope[readScope] {
		t.Errorf("expected %s to be present with requiresConsent=false, got present=%v requiresConsent=%v",
			readScope, scopePresent[readScope], byScope[readScope])
	}
	if !scopePresent[writeScope] || !byScope[writeScope] {
		t.Errorf("expected %s to be present with requiresConsent=true, got present=%v requiresConsent=%v",
			writeScope, scopePresent[writeScope], byScope[writeScope])
	}

	// The documented, deliberate degradation (design doc §2): alreadyGranted
	// is always [] through the BFF proxy, since the CLOSEAUTH_SESSION cookie
	// this cosmetic lookup depends on never reaches the BFF for this
	// cross-origin GET. Not a bug — the accepted trade-off.
	if len(got.AlreadyGranted) != 0 {
		t.Errorf("expected alreadyGranted to always be empty through the BFF proxy (documented degradation), got %v", got.AlreadyGranted)
	}
}
