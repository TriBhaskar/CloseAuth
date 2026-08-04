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

	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestPasswordResetProxy_FullRoundTrip is Stage UI-2c-i, Deliverable 1's
// required proof for the full password-reset journey, through the REAL BFF
// proxy routes (handlePasswordResetRequestProxy/handlePasswordResetConfirmProxy)
// against the REAL harness, reusing UI-2b's Mailpit client:
//
//  1. POST /password-reset/request via the proxy → 200, and a real reset
//     email lands in the real Mailpit container.
//  2. Extract the real token out of that email's `token=` link param
//     (PasswordResetService.resetUrl's confirmed shape — see the stage
//     report for the read-only verification of that link construction).
//  3. POST /password-reset/confirm via the proxy with that real token and a
//     new password → 200.
//  4. Prove the reset genuinely took effect — not just "the backend
//     returned 200" — by driving TWO real login attempts through the BFF's
//     own JSON login route (POST /api/auth/login, Stage UI-2a): the OLD
//     password now fails (401), and the NEW password succeeds (200).
//
// Mirrors the rigor of registration_proxy_test.go's EMAIL_VERIFIED sub-test
// (real Mailpit capture, real token, real confirm) but adds the credential
// re-verification step, since password reset's whole point — unlike email
// verification — is that a DIFFERENT credential must now work and the OLD
// one must not.
func TestPasswordResetProxy_FullRoundTrip(t *testing.T) {
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
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, "http://localhost:45678/callback")
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("password-reset-user")
	const oldPassword = "Old-Password-Pw-123!"
	const newPassword = "New-Password-Pw-456!"
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, oldPassword); err != nil {
		t.Fatalf("create user: %v", err)
	}

	mailpit, err := stack.Mailpit(ctx)
	if err != nil {
		t.Fatalf("build mailpit client: %v", err)
	}

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// ---- step 1: request, via the proxy ----
	requestForm := url.Values{"email": {email}, "client_id": {creds.ClientID}}
	requestReq, err := http.NewRequest(http.MethodPost, ts.URL+"/password-reset/request", strings.NewReader(requestForm.Encode()))
	if err != nil {
		t.Fatalf("build password-reset/request request: %v", err)
	}
	requestReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	requestResp, err := http.DefaultClient.Do(requestReq)
	if err != nil {
		t.Fatalf("proxy password-reset/request: %v", err)
	}
	defer requestResp.Body.Close()
	if requestResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 from password-reset/request via the proxy, got %d", requestResp.StatusCode)
	}

	// ---- step 2: real Mailpit capture, real token extraction ----
	message, err := mailpit.WaitForMessageTo(ctx, email, 30*time.Second)
	if err != nil {
		t.Fatalf("wait for password-reset email: %v", err)
	}
	token, err := testsupport.ExtractLinkParam(message.Text, "token")
	if err != nil {
		t.Fatalf("extract password-reset token: %v", err)
	}
	t.Logf("password-reset/request via proxy: captured a real reset email, extracted token (redacted length=%d)", len(token))

	// ---- step 3: confirm, via the proxy, with the real token ----
	confirmForm := url.Values{"token": {token}, "password": {newPassword}, "client_id": {creds.ClientID}}
	confirmReq, err := http.NewRequest(http.MethodPost, ts.URL+"/password-reset/confirm", strings.NewReader(confirmForm.Encode()))
	if err != nil {
		t.Fatalf("build password-reset/confirm request: %v", err)
	}
	confirmReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	confirmResp, err := http.DefaultClient.Do(confirmReq)
	if err != nil {
		t.Fatalf("proxy password-reset/confirm: %v", err)
	}
	defer confirmResp.Body.Close()
	if confirmResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(confirmResp.Body)
		t.Fatalf("expected 200 confirming the reset with the real token via the proxy, got %d body=%s", confirmResp.StatusCode, body)
	}
	t.Logf("password-reset/confirm via proxy: HTTP 200 (reset with a real token)")

	// ---- step 4a: the OLD password must now fail ----
	oldLoginStatus := jsonLoginStatus(t, ts, email, oldPassword, creds.ClientID)
	if oldLoginStatus != http.StatusUnauthorized {
		t.Fatalf("expected the OLD password to now fail (401) after reset, got %d", oldLoginStatus)
	}
	t.Logf("post-reset login with the OLD password: HTTP %d (correctly rejected)", oldLoginStatus)

	// ---- step 4b: the NEW password must now work ----
	newLoginStatus := jsonLoginStatus(t, ts, email, newPassword, creds.ClientID)
	if newLoginStatus != http.StatusOK {
		t.Fatalf("expected the NEW password to now work (200) after reset, got %d", newLoginStatus)
	}
	t.Logf("post-reset login with the NEW password: HTTP %d (correctly accepted)", newLoginStatus)

	// ---- step 5: an invalid/already-used token confirms generically, no enumeration ----
	reuseForm := url.Values{"token": {token}, "password": {"Another-Pw-789!"}, "client_id": {creds.ClientID}}
	reuseReq, err := http.NewRequest(http.MethodPost, ts.URL+"/password-reset/confirm", strings.NewReader(reuseForm.Encode()))
	if err != nil {
		t.Fatalf("build reused-token confirm request: %v", err)
	}
	reuseReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	reuseResp, err := http.DefaultClient.Do(reuseReq)
	if err != nil {
		t.Fatalf("proxy reused-token confirm: %v", err)
	}
	defer reuseResp.Body.Close()
	if reuseResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected a generic 400 reusing an already-consumed token via the proxy, got %d", reuseResp.StatusCode)
	}
	t.Logf("reused token confirm via proxy: HTTP %d (generic, enumeration-safe rejection)", reuseResp.StatusCode)
}

// jsonLoginStatus drives one real login attempt through the BFF's own JSON
// login route (POST /api/auth/login, handlers_login_json.go — Stage UI-2a)
// and returns the resulting HTTP status: 200 on valid credentials (a
// translated redirect envelope), 401 on invalid ones (relayed unchanged).
// No authorizeQuery is supplied — this only needs to distinguish "the
// password is now correct" from "it isn't," not exercise the OAuth resume
// chain (already proven by login_json_test.go).
func jsonLoginStatus(t *testing.T, ts *httptest.Server, email, password, clientID string) int {
	t.Helper()
	body, err := json.Marshal(map[string]any{
		"email":    email,
		"password": password,
		"clientId": clientID,
	})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/auth/login", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("build login request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST /api/auth/login: %v", err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}
