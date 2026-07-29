package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestBrandingProxy_RoundTrip is Deliverable 2's Go-side "prove it": a thin
// relay addition, proven against the REAL backend exactly like the existing
// login/logout pure-relay pair (auth_proxy_test.go).
//
// This test caught a real, previously-latent bug in internal/proxy: Relay
// (and thus ServeTo) never forwarded the incoming request's QUERY STRING —
// /login and /logout (the only routes relayed before this stage) carry their
// data in a form-encoded body, never a query string, so this path was never
// exercised. GET /branding?client_id=... is the first query-string-bearing
// relay, and it 500'd (a required @RequestParam silently missing
// server-side) until internal/proxy/proxy.go's Relay was fixed to append
// r.URL.RawQuery. See that file's comment for the fix.
func TestBrandingProxy_RoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	ctx := context.Background()

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// ---- unknown client_id: platform-default branding, never a 404/enumeration signal ----
	unknownResp, err := http.Get(ts.URL + "/branding?client_id=" + url.QueryEscape("does-not-exist"))
	if err != nil {
		t.Fatalf("GET /branding (unknown client_id): %v", err)
	}
	defer unknownResp.Body.Close()
	if unknownResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(unknownResp.Body)
		t.Fatalf("expected 200 (platform-default branding for an unknown client_id), got %d body=%s", unknownResp.StatusCode, body)
	}
	unknownBranding := decodeBranding(t, unknownResp.Body)
	// The documented, verified contract (API_REFERENCE.md §1): unset
	// logoUrl/companyName come back as "" (present in the JSON), not absent
	// and not null.
	t.Logf("branding (unknown client_id, platform defaults): %+v", unknownBranding)
	if unknownBranding.PrimaryColor == "" || unknownBranding.BackgroundColor == "" || unknownBranding.AccentColor == "" {
		t.Errorf("expected platform-default colors to be non-empty even when logoUrl/companyName are unset, got %+v", unknownBranding)
	}
	if unknownBranding.LogoURL != "" {
		t.Errorf("expected an unset logoUrl to be the empty string (not e.g. a placeholder), got %q", unknownBranding.LogoURL)
	}

	// ---- a REAL, registered client_id with no branding row set: same
	// platform-default shape, but exercised through the client_id -> tenant
	// resolution path (not the "client unknown at all" path above) ----
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

	knownResp, err := http.Get(ts.URL + "/branding?client_id=" + url.QueryEscape(creds.ClientID))
	if err != nil {
		t.Fatalf("GET /branding (real client_id): %v", err)
	}
	defer knownResp.Body.Close()
	if knownResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(knownResp.Body)
		t.Fatalf("expected 200 for a real, registered client_id, got %d body=%s", knownResp.StatusCode, body)
	}
	knownBranding := decodeBranding(t, knownResp.Body)
	t.Logf("branding (real client_id -> real tenant, no branding row set): %+v", knownBranding)
	if knownBranding != unknownBranding {
		t.Errorf("a tenant with no branding row configured should resolve to the SAME platform defaults as an unknown client_id, got %+v vs %+v",
			knownBranding, unknownBranding)
	}
}

type brandingView struct {
	LogoURL         string `json:"logoUrl"`
	PrimaryColor    string `json:"primaryColor"`
	BackgroundColor string `json:"backgroundColor"`
	AccentColor     string `json:"accentColor"`
	CompanyName     string `json:"companyName"`
}

func decodeBranding(t *testing.T, body io.Reader) brandingView {
	t.Helper()
	var v brandingView
	if err := json.NewDecoder(body).Decode(&v); err != nil {
		t.Fatalf("decode branding response: %v", err)
	}
	return v
}
