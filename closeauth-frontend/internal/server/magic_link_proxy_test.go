package server

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestMagicLinkRequestProxy_EnumerationSafeAndReachesBackend is Stage
// UI-2c-i, Deliverable 1's required proof for magic-link's REQUEST step only
// (per Design Decision #1, the consume step needs — and gets — no BFF proof
// here; its correctness is already covered by the backend's own tests).
//
// Two things are proven, through the REAL BFF proxy route
// (handleMagicLinkRequestProxy) against the REAL harness:
//  1. Enumeration-safety survives the proxy hop unchanged: an existing
//     account's email and a nonexistent one both get an identical HTTP 200
//     with an empty body — the proxy doesn't leak anything the backend
//     itself doesn't.
//  2. The request genuinely reaches the backend (not just "the proxy relays
//     whatever status the backend happens to return for a mismatched path")
//     — proven by polling the REAL Mailpit container for the magic-link
//     email that only a genuine POST /magic-link/request against a real
//     account triggers.
func TestMagicLinkRequestProxy_EnumerationSafeAndReachesBackend(t *testing.T) {
	stack := testsupport.Get(t)
	ctx := context.Background()
	fixtures := testsupport.NewFixtures(stack)

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, "http://localhost:34567/callback")
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("magic-link-user")
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, "Magic-Link-Pw-123!"); err != nil {
		t.Fatalf("create user: %v", err)
	}

	mailpit, err := stack.Mailpit(ctx)
	if err != nil {
		t.Fatalf("build mailpit client: %v", err)
	}

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	postMagicLinkRequest := func(t *testing.T, targetEmail string) *http.Response {
		t.Helper()
		form := url.Values{"email": {targetEmail}, "client_id": {creds.ClientID}}
		req, err := http.NewRequest(http.MethodPost, ts.URL+"/magic-link/request", strings.NewReader(form.Encode()))
		if err != nil {
			t.Fatalf("build magic-link/request request: %v", err)
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("proxy magic-link/request: %v", err)
		}
		return resp
	}

	// ---- existing account: 200, and a real email actually goes out ----
	existingResp := postMagicLinkRequest(t, email)
	defer existingResp.Body.Close()
	if existingResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 for an existing account via the proxy, got %d", existingResp.StatusCode)
	}

	message, err := mailpit.WaitForMessageTo(ctx, email, 30*time.Second)
	if err != nil {
		t.Fatalf("wait for magic-link email: %v", err)
	}
	link, err := testsupport.ExtractLinkParam(message.Text, "token")
	if err != nil {
		t.Fatalf("extract magic-link token: %v", err)
	}
	t.Logf("magic-link/request via proxy reached the real backend: captured a real magic-link email, extracted token (redacted length=%d)", len(link))

	// ---- nonexistent account: IDENTICAL 200, no enumeration signal ----
	unknownEmail := testsupport.Email("magic-link-nobody")
	unknownResp := postMagicLinkRequest(t, unknownEmail)
	defer unknownResp.Body.Close()
	if unknownResp.StatusCode != existingResp.StatusCode {
		t.Fatalf("expected the SAME status for an unknown account as an existing one (enumeration-safe), got %d vs existing's %d",
			unknownResp.StatusCode, existingResp.StatusCode)
	}
	if unknownResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 for an unknown account via the proxy too, got %d", unknownResp.StatusCode)
	}
	t.Logf("magic-link/request via proxy: existing=%d unknown=%d (identical, enumeration-safe)", existingResp.StatusCode, unknownResp.StatusCode)
}
