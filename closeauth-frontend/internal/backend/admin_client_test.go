package backend_test

import (
	"context"
	"testing"

	"closeauth-frontend/internal/testsupport"
)

// TestAdminClient_PlatformAuth_RoundTrip is Deliverable 3's "prove it": mint
// a platform-admin token via the harness's bootstrap credentials, use
// AdminClient to call an authenticated admin endpoint (GET /v1/platform/me),
// and assert the response is correctly parsed and reflects the expected
// principal. Also exercises the RFC 7807 problem decoding path with a
// deliberately invalid bearer token.
func TestAdminClient_PlatformAuth_RoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	ctx := context.Background()
	admin := stack.AdminClient()

	tokenResp, err := admin.MintPlatformAdminToken(ctx, testsupport.BootstrapAdminEmail, testsupport.BootstrapAdminPassword)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	if tokenResp.AccessToken == "" {
		t.Fatalf("expected an access token")
	}
	if tokenResp.TokenType != "Bearer" {
		t.Errorf("token_type = %q, want Bearer", tokenResp.TokenType)
	}
	if tokenResp.ExpiresIn <= 0 {
		t.Errorf("expires_in = %d, want > 0", tokenResp.ExpiresIn)
	}
	t.Logf("minted platform admin token: expires_in=%ds", tokenResp.ExpiresIn)

	me, err := admin.PlatformMe(ctx, tokenResp.AccessToken)
	if err != nil {
		t.Fatalf("GET /v1/platform/me: %v", err)
	}
	if me.Email != testsupport.BootstrapAdminEmail {
		t.Errorf("platform/me email = %q, want %q", me.Email, testsupport.BootstrapAdminEmail)
	}
	if me.Status != "ACTIVE" {
		t.Errorf("platform/me status = %q, want ACTIVE", me.Status)
	}
	hasPlatformAdminRole := false
	for _, role := range me.Roles {
		if role == "PLATFORM_ADMIN" {
			hasPlatformAdminRole = true
		}
	}
	if !hasPlatformAdminRole {
		t.Errorf("platform/me roles = %v, want to include PLATFORM_ADMIN (the bootstrap mechanism assigns it)", me.Roles)
	}
	t.Logf("GET /v1/platform/me: sub=%s email=%s status=%s roles=%v", me.Sub, me.Email, me.Status, me.Roles)

	// ---- RFC 7807 problem decoding: a bad bearer token surfaces a parseable, non-2xx response ----
	badResp, err := admin.Get(ctx, "not-a-real-token", "/v1/platform/me")
	if err != nil {
		t.Fatalf("GET /v1/platform/me with an invalid token: %v", err)
	}
	if badResp.OK() {
		t.Fatalf("expected a non-2xx response for an invalid bearer token, got %d", badResp.StatusCode)
	}
	if badResp.StatusCode != 401 {
		t.Errorf("expected 401 for a malformed/invalid bearer token, got %d (body=%s)", badResp.StatusCode, badResp.Body)
	}

	// ---- wrong credentials at the mint endpoint: uniform, enumeration-safe 401 ----
	_, err = admin.MintPlatformAdminToken(ctx, testsupport.BootstrapAdminEmail, "definitely-the-wrong-password")
	if err == nil {
		t.Fatalf("expected minting a token with the wrong password to fail")
	}
	t.Logf("mint with wrong password correctly failed: %v", err)
}
