package server

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestEntryResolveProxy_UnknownAndMalformedAreBothPlain404s is FE-2a's
// Go-side "prove it" — same convention as TestBrandingProxy_RoundTrip: a
// thin relay addition, proven against the REAL backend. No currently
// provisioned tenant has a ten_-prefixed slug yet (BE-A hasn't shipped —
// see the FE-2a plan's own decision on this), so there's no "known, ACTIVE
// tenant" case to exercise here yet; that gap closes automatically once
// BE-A lands, with zero changes needed on either side of this proxy.
func TestEntryResolveProxy_UnknownAndMalformedAreBothPlain404s(t *testing.T) {
	stack := testsupport.Get(t)

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	cases := []struct {
		name     string
		tenantID string
	}{
		{"unknown tenantId", "ten_does-not-exist"},
		// This handler now re-normalises before relaying (spec §6.1's Data
		// line — see handleEntryResolveProxy's doc comment), so a bare,
		// unprefixed value like "not-prefixed" becomes "ten_not-prefixed"
		// and reaches the backend VALID-SHAPED — still 404, but now because
		// no such tenant exists, not because the shape was rejected. A
		// value with a genuinely disallowed character stays malformed even
		// after normalisation, proving the backend's own strict regex still
		// guards independent of this BFF-side normalisation.
		{"malformed tenantId (no ten_ prefix)", "not-prefixed"},
		{"malformed tenantId (disallowed character survives normalisation)", "ten_bad_char!"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp, err := http.Get(ts.URL + "/api/entry/resolve?tenantId=" + tc.tenantID)
			if err != nil {
				t.Fatalf("GET /api/entry/resolve: %v", err)
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)

			if resp.StatusCode != http.StatusNotFound {
				t.Errorf("status = %d, want 404, body=%s", resp.StatusCode, body)
			}
			if len(body) != 0 {
				t.Errorf("body = %q, want empty (matching EntryController's byte-identical-404 contract)", body)
			}
		})
	}
}

// TestEntryResolveProxy_RateLimitedRequestGetsTheSame404ShapeAsUnknown proves
// spec §6.1's requirement end-to-end through the real router wiring (not
// just the middleware unit tests in internal/middleware/ratelimit_test.go):
// the 21st request in a minute from one IP must be indistinguishable from an
// unknown-tenant lookup — never a 429, which would itself be a signal.
func TestEntryResolveProxy_RateLimitedRequestGetsTheSame404ShapeAsUnknown(t *testing.T) {
	stack := testsupport.Get(t)

	// A fresh Server (and therefore a fresh, empty rate limiter — it's a
	// local var inside RegisterRoutes) per test function, so this test's 25
	// requests can never be polluted by another test's count.
	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	url := ts.URL + "/api/entry/resolve?tenantId=ten_rate-limit-probe"
	var lastStatus int
	var lastBody []byte
	for i := 0; i < 25; i++ {
		resp, err := http.Get(url)
		if err != nil {
			t.Fatalf("request %d: %v", i+1, err)
		}
		lastBody, _ = io.ReadAll(resp.Body)
		resp.Body.Close()
		lastStatus = resp.StatusCode
	}

	if lastStatus != http.StatusNotFound {
		t.Fatalf("the 25th request within the rate-limit window: status = %d, want 404 (same shape as unknown, never 429)", lastStatus)
	}
	if len(lastBody) != 0 {
		t.Errorf("rate-limited response body = %q, want empty", lastBody)
	}
}
