package testsupport

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/google/uuid"

	"closeauth-frontend/internal/backend"
)

// Suffix returns a short, unique, identifier-safe suffix — THE isolation
// mechanism every fixture below relies on, mirroring the Java IT module's
// Fixtures.suffix(): a Stack (stack.go) is shared across every test in a
// package (booting is slow), so tests must never assume a clean database
// between tests — each creates its own fresh tenant/user/client with a
// random suffix so tests never collide or depend on execution order.
func Suffix() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")[:10]
}

// Email returns a unique, lowercase email address (the backend normalizes
// emails to lowercase, so DB/API lookups match).
func Email(local string) string {
	return strings.ToLower(fmt.Sprintf("%s-%s@example.test", local, Suffix()))
}

// Slug returns a unique tenant slug (lowercase alphanumerics + hyphen).
func Slug(base string) string {
	return strings.ToLower(fmt.Sprintf("%s-%s", base, Suffix()))
}

// ClientID returns a unique OAuth2 client id.
func ClientID(base string) string {
	return fmt.Sprintf("%s-%s", base, Suffix())
}

// AdminConsoleClientID returns the deterministic per-tenant admin-console
// client id Option A auto-provisions for slug, matching
// AdminConsoleClientProvisioningCallback.CLIENT_ID_PREFIX + tenant.getSlug()
// on the backend (and config.BFFConfig.AdminClientID on the BFF side)
// exactly.
func AdminConsoleClientID(slug string) string {
	return "admin-console-" + slug
}

// ClientCredentials is a registered client's id + plaintext secret (the
// secret is returned only at creation time — see API_REFERENCE.md §4.5).
type ClientCredentials struct {
	ClientID string
	Secret   string
}

// Fixtures builds disposable tenant/client/user fixtures via the REAL admin
// API — the Go equivalent of the Java IT module's AdminApiClient fixture
// builders (provisionActiveTenant / registerConfidentialClient /
// createActiveUser). Deliberately admin-API-driven rather than re-driving
// the registration/verification flow — "don't re-prove what's already
// proven" (that flow is a later stage's own proof, not this one's job).
type Fixtures struct {
	admin *backend.AdminClient
}

// NewFixtures constructs a Fixtures helper against stack's admin API.
func NewFixtures(stack *Stack) *Fixtures {
	return &Fixtures{admin: stack.AdminClient()}
}

// PlatformAdminToken mints a bearer token for the harness's bootstrap
// platform admin (POST /v1/platform/auth/token, permitAll — the one
// unauthenticated /v1 endpoint).
func (f *Fixtures) PlatformAdminToken(ctx context.Context) (string, error) {
	token, err := f.admin.MintPlatformAdminToken(ctx, BootstrapAdminEmail, BootstrapAdminPassword)
	if err != nil {
		return "", fmt.Errorf("mint bootstrap platform admin token: %w", err)
	}
	return token.AccessToken, nil
}

// ProvisionActiveTenant provisions a tenant (via the platform token) and
// activates it, returning the tenant id.
//
// A thin wrapper over ProvisionActiveTenantWithSlug that discards the slug —
// kept so this function's 6 pre-existing call sites (predating stage UI-3a,
// which is the first caller that needs the slug back) don't need to change.
func (f *Fixtures) ProvisionActiveTenant(ctx context.Context, platformToken string) (string, error) {
	tenantID, _, err := f.ProvisionActiveTenantWithSlug(ctx, platformToken)
	return tenantID, err
}

// ProvisionActiveTenantWithSlug is ProvisionActiveTenant, additionally
// returning the tenant's slug — needed by stage UI-3a's admin-console
// journeys, whose entire routing/client-id scheme (admin-console-{slug},
// /t/{slug}/...) is slug-keyed, not id-keyed.
func (f *Fixtures) ProvisionActiveTenantWithSlug(ctx context.Context, platformToken string) (tenantID, slug string, err error) {
	slug = Slug("t")
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/platform/tenants", map[string]string{
		"slug": slug,
		"name": "Tenant " + slug,
	})
	if err != nil {
		return "", "", fmt.Errorf("provision tenant: %w", err)
	}
	if resp.StatusCode != 201 {
		return "", "", fmt.Errorf("provision tenant: %s", describeFailure(resp))
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := resp.JSON(&created); err != nil {
		return "", "", fmt.Errorf("provision tenant: decode response: %w", err)
	}

	activateResp, err := f.admin.Post(ctx, platformToken, "/v1/platform/tenants/"+created.ID+"/activate")
	if err != nil {
		return "", "", fmt.Errorf("activate tenant: %w", err)
	}
	if activateResp.StatusCode != 200 {
		return "", "", fmt.Errorf("activate tenant: %s", describeFailure(activateResp))
	}
	return created.ID, slug, nil
}

// AssignTenantAdmin grants userID the tenant's built-in TENANT_ADMIN system
// role (seeded on every tenant by the backend's role-starter-pack
// provisioning callback — see rbac.service.RoleStarterPackProvisioningCallback).
// Mirrors the Java IT module's AdminApiClient.assignTenantRole: look the role
// up by name (GET .../roles), then assign it (POST .../tenant-roles/{roleId},
// 204) — no such helper existed in this package before stage UI-3a, whose
// admin-console journeys need a real TENANT_ADMIN (and, for the refusal
// proof, a user WITHOUT this call) to drive the callback's verification
// triple.
func (f *Fixtures) AssignTenantAdmin(ctx context.Context, platformToken, tenantID, userID string) error {
	const roleName = "TENANT_ADMIN"

	resp, err := f.admin.GetQuery(ctx, platformToken, "/v1/tenants/"+tenantID+"/roles", url.Values{"size": {"100"}})
	if err != nil {
		return fmt.Errorf("list tenant roles: %w", err)
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("list tenant roles: %s", describeFailure(resp))
	}
	var page struct {
		Items []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"items"`
	}
	if err := resp.JSON(&page); err != nil {
		return fmt.Errorf("list tenant roles: decode response: %w", err)
	}
	var roleID string
	for _, role := range page.Items {
		if role.Name == roleName {
			roleID = role.ID
			break
		}
	}
	if roleID == "" {
		return fmt.Errorf("assign tenant admin: no %s role found among tenant %s's roles", roleName, tenantID)
	}

	assignResp, err := f.admin.Post(ctx, platformToken, "/v1/tenants/"+tenantID+"/users/"+userID+"/tenant-roles/"+roleID)
	if err != nil {
		return fmt.Errorf("assign tenant admin: %w", err)
	}
	if assignResp.StatusCode != 204 {
		return fmt.Errorf("assign tenant admin: %s", describeFailure(assignResp))
	}
	return nil
}

// RegisterConfidentialClient registers a confidential (secret), PKCE-required,
// trusted client on tenantID and returns its credentials. Confidential so the
// backend issues a refresh token (SAS does not issue refresh tokens to public
// clients — needed to prove refresh rotation); trusted so consent is skipped
// (out of scope this stage); PKCE required to match OAuthClient's flow.
//
// UI-3c: the backend now generates the secret server-side and ignores any
// caller-supplied value (ClientSecretGenerator, closeauth-backend) —
// "publicClient: false" replaces the old "send a clientSecret" contract, and
// the secret is read back out of the 201 response, mirroring the same fix
// applied to closeauth-integration-tests' AdminApiClient.registerClient.
func (f *Fixtures) RegisterConfidentialClient(ctx context.Context, platformToken, tenantID, redirectURI string) (ClientCredentials, error) {
	clientID := ClientID("c")
	body := map[string]any{
		"clientId":        clientID,
		"clientName":      "Client " + clientID,
		"publicClient":    false,
		"grantTypes":      []string{"authorization_code", "refresh_token"},
		"scopes":          []string{"openid"},
		"redirectUris":    []string{redirectURI},
		"requireProofKey": true,
		"trusted":         true,
	}
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/clients", body)
	if err != nil {
		return ClientCredentials{}, fmt.Errorf("register client: %w", err)
	}
	if resp.StatusCode != 201 {
		return ClientCredentials{}, fmt.Errorf("register client: %s", describeFailure(resp))
	}
	var created struct {
		ClientSecret string `json:"clientSecret"`
	}
	if err := resp.JSON(&created); err != nil {
		return ClientCredentials{}, fmt.Errorf("register client: decode response: %w", err)
	}
	if created.ClientSecret == "" {
		return ClientCredentials{}, fmt.Errorf("register client: response carried no clientSecret")
	}
	return ClientCredentials{ClientID: clientID, Secret: created.ClientSecret}, nil
}

// CreateActiveUser admin-creates an ACTIVE password user in tenantID and
// returns the user id. Does NOT drive the registration/verification flow —
// a direct admin-API create, matching the "don't re-prove what's already
// proven" discipline documented across this project's other test suites.
func (f *Fixtures) CreateActiveUser(ctx context.Context, platformToken, tenantID, email, password string) (string, error) {
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/users", map[string]string{
		"email":         email,
		"password":      password,
		"initialStatus": "ACTIVE",
	})
	if err != nil {
		return "", fmt.Errorf("create user: %w", err)
	}
	if resp.StatusCode != 201 {
		return "", fmt.Errorf("create user: %s", describeFailure(resp))
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := resp.JSON(&created); err != nil {
		return "", fmt.Errorf("create user: decode response: %w", err)
	}
	return created.ID, nil
}

// SetRegistrationMode sets tenantID's registration mode via the admin API
// (PUT /v1/tenants/{tenantId}/registration-config, RequiresTenantAccess —
// the platform token satisfies that gate for any tenant). mode is one of
// OPEN / EMAIL_VERIFIED / ADMIN_APPROVED / INVITE_ONLY (API_REFERENCE.md
// §4.6) — Deliverable 3 (registration_proxy_test.go) drives all four through
// this one helper rather than four bespoke ones.
func (f *Fixtures) SetRegistrationMode(ctx context.Context, platformToken, tenantID, mode string) error {
	resp, err := f.admin.PutJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/registration-config", map[string]string{
		"mode": mode,
	})
	if err != nil {
		return fmt.Errorf("set registration mode: %w", err)
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("set registration mode: %s", describeFailure(resp))
	}
	return nil
}

// IssueInvite issues an INVITE_ONLY registration invite for email via the
// admin API (POST /v1/tenants/{tenantId}/invites, 201 — API_REFERENCE.md
// §4.6). Mirrors the Java IT module's InviteService fixture usage: the raw
// invite secret is never in this response (emailed only), so Deliverable 3's
// test still has to go via Mailpit (ExtractLinkParam(body, "invite")) to get
// a usable token — this only proves issuance, not the token itself.
func (f *Fixtures) IssueInvite(ctx context.Context, platformToken, tenantID, email string) (string, error) {
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/tenants/"+tenantID+"/invites", map[string]string{
		"email": email,
	})
	if err != nil {
		return "", fmt.Errorf("issue invite: %w", err)
	}
	if resp.StatusCode != 201 {
		return "", fmt.Errorf("issue invite: %s", describeFailure(resp))
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := resp.JSON(&created); err != nil {
		return "", fmt.Errorf("issue invite: decode response: %w", err)
	}
	return created.ID, nil
}

// BootstrapTenantAdminResult is the temp credential + user identity created
// by TenantOnboardingService.bootstrapFirstAdmin — what the password-
// rotation proxy tests need to then drive a rotation-required login.
type BootstrapTenantAdminResult struct {
	UserID            string
	Email             string
	TemporaryPassword string
}

// BootstrapTenantAdmin drives the platform-admin "bootstrap first admin"
// endpoint (POST /v1/platform/tenants/{tenantId}/bootstrap-admin,
// @RequiresPlatformAdmin) — the ONLY way a user enters the
// must_change_password state Phase 4a's password-rotation proxy tests need
// to exercise the flow end to end. Mirrors TenantOnboardingService.
// bootstrapFirstAdmin's own contract: 201, {user: {...}, temporaryPassword,
// temporaryPasswordExpiresAt}.
func (f *Fixtures) BootstrapTenantAdmin(ctx context.Context, platformToken, tenantID, email string) (BootstrapTenantAdminResult, error) {
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/platform/tenants/"+tenantID+"/bootstrap-admin", map[string]string{
		"email": email,
	})
	if err != nil {
		return BootstrapTenantAdminResult{}, fmt.Errorf("bootstrap tenant admin: %w", err)
	}
	if resp.StatusCode != 201 {
		return BootstrapTenantAdminResult{}, fmt.Errorf("bootstrap tenant admin: %s", describeFailure(resp))
	}
	var created struct {
		User struct {
			ID    string `json:"id"`
			Email string `json:"email"`
		} `json:"user"`
		TemporaryPassword string `json:"temporaryPassword"`
	}
	if err := resp.JSON(&created); err != nil {
		return BootstrapTenantAdminResult{}, fmt.Errorf("bootstrap tenant admin: decode response: %w", err)
	}
	if created.TemporaryPassword == "" {
		return BootstrapTenantAdminResult{}, fmt.Errorf("bootstrap tenant admin: response carried no temporaryPassword")
	}
	return BootstrapTenantAdminResult{
		UserID:            created.User.ID,
		Email:             created.User.Email,
		TemporaryPassword: created.TemporaryPassword,
	}, nil
}

func describeFailure(resp backend.APIResponse) string {
	if problem, err := resp.Problem(); err == nil && problem.Code != "" {
		return fmt.Sprintf("HTTP %d %s: %s", resp.StatusCode, problem.Code, problem.Detail)
	}
	return fmt.Sprintf("HTTP %d: %s", resp.StatusCode, resp.Body)
}
