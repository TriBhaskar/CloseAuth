package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/config"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestHandleAuthorizeStart_MalformedTenantIdIsRejectedBeforeTouchingOAuthClient
// proves validTenantID runs before anything that needs a configured
// oauthClient/adminClient — the bare &Server{} below has neither, so a 400
// here (rather than a nil-pointer panic) is itself the proof. No Docker
// needed: this is unit-level, mirroring the reasoning
// handlers_authorize_start.go's own doc comment gives for checking shape
// first.
func TestHandleAuthorizeStart_MalformedTenantIdIsRejectedBeforeTouchingOAuthClient(t *testing.T) {
	s := &Server{}

	cases := []string{
		"acme-inc",                     // no ten_ prefix (today's raw slug shape)
		"ten_",                         // prefix only, no body
		"ten_A",                        // uppercase
		"",                             // empty
		`ten_"; DROP TABLE tenants;--`, // injection-shaped
	}

	for _, tenantID := range cases {
		t.Run(tenantID, func(t *testing.T) {
			body, _ := json.Marshal(map[string]string{"tenantId": tenantID})
			req := httptest.NewRequest(http.MethodPost, "/api/auth/authorize/start", bytes.NewReader(body))
			rec := httptest.NewRecorder()

			s.handleAuthorizeStart(rec, req)

			if rec.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400 for tenantId=%q", rec.Code, tenantID)
			}
		})
	}
}

// TestHandleAuthorizeStart_MalformedJSONBodyIs400 is the companion case one
// level earlier than tenantId validation: a body that isn't even valid JSON.
func TestHandleAuthorizeStart_MalformedJSONBodyIs400(t *testing.T) {
	s := &Server{}
	req := httptest.NewRequest(http.MethodPost, "/api/auth/authorize/start", bytes.NewReader([]byte("not json")))
	rec := httptest.NewRecorder()

	s.handleAuthorizeStart(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

// newAuthorizeStartTestServer wires a real Server against stack's real
// backend, the same bffCfg shape newAdminConsoleServer (admin_console_test.go)
// uses, so preflightTenant's GET /login round trip hits an actual running
// backend rather than a stub.
func newAuthorizeStartTestServer(stack *testsupport.Stack) *Server {
	bffCfg := &config.BFFConfig{
		BaseURL:             stack.BFFBaseURL(),
		AdminCallbackPath:   "/admin/callback",
		AdminClientIDPrefix: "admin-console-",
		AdminScope:          "openid profile",
		SessionMaxAge:       12 * time.Hour,
		OAuthContextTTL:     10 * time.Minute,
		ReauthSkew:          30 * time.Second,
		IsProduction:        false,
	}
	return &Server{
		authProxy:   proxy.New(stack.AppBaseURI() + stack.ContextPath()),
		bff:         bffCfg,
		oauthClient: backend.NewOAuthClient(stack.AppBaseURI(), stack.ContextPath(), bffCfg.AdminCallbackURL()),
		adminClient: backend.NewAdminClient(stack.AppBaseURI(), stack.ContextPath()),
	}
}

// TestAuthorizeStart_UnknownTenantIdIs404 is FE-2a's Go-side "prove it" for
// the endpoint's real backend round trip. No currently provisioned tenant
// has a ten_-prefixed slug yet (BE-A hasn't shipped, same gap
// entry_resolve_proxy_test.go documents), so this can't yet prove a
// successful authorize-start for a real, ACTIVE tenant — only that a
// well-formed-but-nonexistent tenantId correctly comes back unknown via a
// real preflightTenant round trip against the real backend's GET /login.
// That gap closes automatically once BE-A lands, with zero changes needed
// here.
func TestAuthorizeStart_UnknownTenantIdIs404(t *testing.T) {
	stack := testsupport.Get(t)

	s := newAuthorizeStartTestServer(stack)
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	reqBody, _ := json.Marshal(map[string]string{"tenantId": "ten_does-not-exist"})
	resp, err := http.Post(ts.URL+"/api/auth/authorize/start", "application/json", bytes.NewReader(reqBody))
	if err != nil {
		t.Fatalf("POST /api/auth/authorize/start: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("status = %d, want 404", resp.StatusCode)
	}

	var body struct {
		Error string `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body.Error != "unknown_tenant" {
		t.Errorf("error = %q, want unknown_tenant", body.Error)
	}
}

// TestAuthorizeStart_RateLimitedRequestIs429 proves the endpoint's own rate
// limiter (routes.go's authorizeStartLimiter, separate from entry-resolve's)
// blocks the 21st request in a minute from one IP — with a plain 429, unlike
// entry-resolve's byte-identical-404 shape, because this endpoint isn't a
// tenant-existence oracle in the same sense (a caller here already knows a
// concrete tenantId it's trying to start a login for).
func TestAuthorizeStart_RateLimitedRequestIs429(t *testing.T) {
	stack := testsupport.Get(t)

	// A fresh Server (and therefore a fresh, empty rate limiter — it's a
	// local var inside RegisterRoutes) per test function, so this test's 25
	// requests can never be polluted by another test's count.
	s := newAuthorizeStartTestServer(stack)
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	reqBody, _ := json.Marshal(map[string]string{"tenantId": "ten_rate-limit-probe"})
	var lastStatus int
	for i := 0; i < 25; i++ {
		resp, err := http.Post(ts.URL+"/api/auth/authorize/start", "application/json", bytes.NewReader(reqBody))
		if err != nil {
			t.Fatalf("request %d: %v", i+1, err)
		}
		resp.Body.Close()
		lastStatus = resp.StatusCode
	}

	if lastStatus != http.StatusTooManyRequests {
		t.Fatalf("the 25th request within the rate-limit window: status = %d, want 429", lastStatus)
	}
}
