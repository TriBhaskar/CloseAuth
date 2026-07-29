package backend_test

import (
	"context"
	"testing"
	"time"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/testsupport"
)

// TestOAuthClient_LoginAndRefresh_RoundTrip is Deliverable 2's "prove it": a
// real round trip against the Stage UI-1 harness — create a disposable
// tenant/client/user, drive the full Authorization Code + PKCE flow
// (authorize -> login -> capture code -> exchange -> tokens), assert the
// resulting token's claims are correctly shaped per the Session model, then
// use the refresh token to obtain a genuinely different access token.
//
// This test package is deliberately "backend_test" (external), not
// "backend": internal/testsupport imports internal/backend to build its
// OAuthClient/AdminClient accessors, so an internal ("backend") test file
// importing testsupport would set up backend(+tests) -> testsupport ->
// backend, which Go's test tooling does support but which reads confusingly;
// the external test package makes the dependency direction unambiguous.
func TestOAuthClient_LoginAndRefresh_RoundTrip(t *testing.T) {
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
	const redirectURI = "http://localhost:12345/callback"
	creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, redirectURI)
	if err != nil {
		t.Fatalf("register client: %v", err)
	}
	email := testsupport.Email("oauth-user")
	password := "Oauth-User-Pw-123!"
	userID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password)
	if err != nil {
		t.Fatalf("create user: %v", err)
	}

	oauth := stack.OAuthClient(redirectURI)

	loginResult, err := oauth.Login(ctx, creds.ClientID, creds.Secret, email, password)
	if err != nil {
		t.Fatalf("oauth login: %v", err)
	}
	tokens := loginResult.Tokens
	if tokens.AccessToken == "" {
		t.Fatalf("expected an access token")
	}
	if tokens.RefreshToken == "" {
		t.Fatalf("expected a refresh token (client is confidential)")
	}
	t.Logf("login ok: token_type=%s scope=%q session_key=%s", tokens.TokenType, tokens.Scope, loginResult.Session.SessionKey())

	// ---- assert the token's claims are correctly shaped (per the Session model, session.go) ----
	claims, err := backend.DecodeJWTClaims(tokens.AccessToken)
	if err != nil {
		t.Fatalf("decode access token claims: %v", err)
	}
	if got := claims.String("sub"); got != userID {
		t.Errorf("sub claim = %q, want the created user's id %q", got, userID)
	}
	if !claims.Has("tenant_id") {
		t.Errorf("expected a tenant_id claim on a tenant-user token (its ABSENCE is the platform-admin signal)")
	}
	if got := claims.String("tenant_id"); got != tenantID {
		t.Errorf("tenant_id claim = %q, want %q", got, tenantID)
	}
	if claims.String("token_use") == "platform_admin" {
		t.Errorf("a tenant-user token must not carry token_use=platform_admin")
	}
	if amr := claims.StringSlice("amr"); len(amr) != 1 || amr[0] != "pwd" {
		t.Errorf("amr claim = %v, want [pwd] for a password login", amr)
	}
	if _, ok := claims["tenant_roles"]; !ok {
		t.Errorf("expected a tenant_roles claim (even if empty) — CloseAuthTokenCustomizer always stamps it")
	}
	if _, ok := claims["roles"]; !ok {
		t.Errorf("expected a roles (platform roles) claim (even if empty) — CloseAuthTokenCustomizer always stamps it")
	}

	// The JWT itself carries no email claim (confirmed against CloseAuthTokenCustomizer) — Session.Email
	// must be backfilled via GET /v1/me. Prove that round trip too.
	me, err := stack.AdminClient().Me(ctx, tokens.AccessToken)
	if err != nil {
		t.Fatalf("GET /v1/me: %v", err)
	}
	if me.Email != email {
		t.Errorf("GET /v1/me email = %q, want %q", me.Email, email)
	}

	session := backend.Session{
		PrincipalType: backend.PrincipalTypeTenantUser,
		UserID:        claims.String("sub"),
		Email:         me.Email,
		TenantID:      claims.String("tenant_id"),
		TenantRoles:   claims.StringSlice("tenant_roles"),
		PlatformRoles: claims.StringSlice("roles"),
		AccessToken:   tokens.AccessToken,
		RefreshToken:  tokens.RefreshToken,
		CreatedAt:     time.Now(),
	}
	if !session.Refreshable() {
		t.Fatalf("expected a refreshable session (confidential client) — RefreshToken must not be empty")
	}

	// ---- refresh: confirm the new access token is genuinely different, not a no-op ----
	refreshed, err := oauth.Refresh(ctx, creds.ClientID, creds.Secret, tokens.RefreshToken)
	if err != nil {
		t.Fatalf("oauth refresh: %v", err)
	}
	if refreshed.AccessToken == "" {
		t.Fatalf("expected a new access token from refresh")
	}
	if refreshed.AccessToken == tokens.AccessToken {
		t.Errorf("refresh returned the SAME access token — expected a genuinely new one")
	}
	if refreshed.RefreshToken == "" {
		t.Errorf("expected refresh rotation to issue a new refresh token")
	}
	if refreshed.RefreshToken == tokens.RefreshToken {
		t.Errorf("refresh returned the SAME refresh token — expected rotation to issue a new one")
	}
	t.Logf("refresh ok: new access token differs (len %d vs %d), new refresh token differs",
		len(refreshed.AccessToken), len(tokens.AccessToken))
}
