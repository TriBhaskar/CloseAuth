package testsupport

import (
	"context"
	"fmt"
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
func (f *Fixtures) ProvisionActiveTenant(ctx context.Context, platformToken string) (string, error) {
	slug := Slug("t")
	resp, err := f.admin.PostJSON(ctx, platformToken, "/v1/platform/tenants", map[string]string{
		"slug": slug,
		"name": "Tenant " + slug,
	})
	if err != nil {
		return "", fmt.Errorf("provision tenant: %w", err)
	}
	if resp.StatusCode != 201 {
		return "", fmt.Errorf("provision tenant: %s", describeFailure(resp))
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := resp.JSON(&created); err != nil {
		return "", fmt.Errorf("provision tenant: decode response: %w", err)
	}

	activateResp, err := f.admin.Post(ctx, platformToken, "/v1/platform/tenants/"+created.ID+"/activate")
	if err != nil {
		return "", fmt.Errorf("activate tenant: %w", err)
	}
	if activateResp.StatusCode != 200 {
		return "", fmt.Errorf("activate tenant: %s", describeFailure(activateResp))
	}
	return created.ID, nil
}

// RegisterConfidentialClient registers a confidential (secret), PKCE-required,
// trusted client on tenantID and returns its credentials. Confidential so the
// backend issues a refresh token (SAS does not issue refresh tokens to public
// clients — needed to prove refresh rotation); trusted so consent is skipped
// (out of scope this stage); PKCE required to match OAuthClient's flow.
func (f *Fixtures) RegisterConfidentialClient(ctx context.Context, platformToken, tenantID, redirectURI string) (ClientCredentials, error) {
	clientID := ClientID("c")
	secret := "secret-" + Suffix()
	body := map[string]any{
		"clientId":        clientID,
		"clientName":      "Client " + clientID,
		"clientSecret":    secret,
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
	return ClientCredentials{ClientID: clientID, Secret: secret}, nil
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

func describeFailure(resp backend.APIResponse) string {
	if problem, err := resp.Problem(); err == nil && problem.Code != "" {
		return fmt.Sprintf("HTTP %d %s: %s", resp.StatusCode, problem.Code, problem.Detail)
	}
	return fmt.Sprintf("HTTP %d: %s", resp.StatusCode, resp.Body)
}
