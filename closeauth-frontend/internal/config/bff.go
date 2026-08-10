package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// BFFConfig holds the configuration for the BFF's own role as a real OAuth2
// client (stage UI-3a: the tenant-admin console). Unlike BackendConfig (where
// to find the backend) or ServerConfig (how this process's own HTTP server
// listens), nothing describing the BFF's admin-console OAuth client — its
// public base URL, its fixed callback path, session lifetimes, the lazy
// re-auth skew — existed anywhere in this package before this stage.
// OAUTH_CONTEXT_ENCRYPTION_KEY remains read directly via os.Getenv inside
// middleware.GetEncryptionKey() (not moved here) to keep this change minimal;
// see that function's doc comment.
type BFFConfig struct {
	// BaseURL is this BFF's own externally-reachable scheme+host+port, e.g.
	// http://localhost:8080 in production or http://localhost:5173 when
	// developing against the Vite dev server (see vite.config.ts's proxy
	// rules and the stage report's dev-mode coupling note). No trailing
	// slash. Every URL the BFF hands the browser to navigate to (admin
	// login/reauth targets, the callback registered on the backend) is
	// built from this value, so it MUST equal whatever origin the browser
	// is actually being served from.
	BaseURL string

	// AdminCallbackPath is the BFF-side path the backend redirects to after
	// the admin-console authorization code flow. It is fixed and NOT
	// slug-aware by construction: Option A registers exactly one redirect_uri
	// per tenant client (closeauth.bff.admin-callback on the backend), so
	// every tenant's admin-console client shares this single callback path.
	// The slug is recovered from the encrypted oauth_ctx_{slug} cookie /
	// the state parameter, not from this path.
	AdminCallbackPath string

	// AdminClientIDPrefix must equal AdminConsoleClientProvisioningCallback's
	// CLIENT_ID_PREFIX on the backend ("admin-console-"). Kept as config
	// (not a hardcoded literal in the handlers) so a mismatch is visible in
	// one place if the backend's prefix ever changes.
	AdminClientIDPrefix string

	// AdminScope is the exact scope string requested on every admin-console
	// /oauth2/authorize call. Must be a subset of (here, exactly equal to)
	// the client's registered scopes ("openid", "profile" — Option A report
	// §1) or the backend rejects the request.
	AdminScope string

	// SessionMaxAge bounds the BFF's own bff_admin_session cookie lifetime.
	// Defaults to 12h to mirror the backend's own absolute session cap
	// (closeauth.session.absolute-timeout) — there is no point in the BFF
	// cookie outliving the backend session it is a client of.
	SessionMaxAge time.Duration

	// OAuthContextTTL bounds how long a pending login/reauth round trip
	// (the encrypted oauth_ctx_{slug} cookie) stays valid before it's
	// rejected as stale. Wired into middleware.SetOAuthContextTTL at
	// startup — see server.go.
	OAuthContextTTL time.Duration

	// ReauthSkew is the buffer subtracted from the access token's expiry
	// when deciding whether a session needs silent re-authorization
	// (AdminSession.NeedsReauth). Re-auth is triggered lazily — only when a
	// request actually needs a token and it is expired or within this
	// window of expiring — never on a fixed timer, so a user mid-form is
	// never interrupted.
	ReauthSkew time.Duration

	// IsProduction gates cookie Secure flags across every cookie this
	// package's callers set (session, oauth-context, denial marker, CSRF).
	// Derived from ENVIRONMENT, matching the one place server.go already
	// reads that variable (previously only for a log line).
	IsProduction bool
}

// LoadBFFConfig loads the BFF's own OAuth-client configuration from
// environment variables with sensible defaults for local development.
func LoadBFFConfig() *BFFConfig {
	return &BFFConfig{
		BaseURL:             strings.TrimRight(getEnvString("BFF_BASE_URL", "http://localhost:8080"), "/"),
		AdminCallbackPath:   getEnvString("BFF_ADMIN_CALLBACK_PATH", "/admin/callback"),
		AdminClientIDPrefix: getEnvString("BFF_ADMIN_CLIENT_ID_PREFIX", "admin-console-"),
		AdminScope:          getEnvString("BFF_ADMIN_SCOPE", "openid profile"),
		SessionMaxAge:       getEnvDuration("BFF_ADMIN_SESSION_MAX_AGE", 12*time.Hour),
		OAuthContextTTL:     getEnvDuration("BFF_OAUTH_CONTEXT_TTL", 10*time.Minute),
		ReauthSkew:          getEnvDuration("BFF_REAUTH_SKEW", 30*time.Second),
		IsProduction:        strings.EqualFold(os.Getenv("ENVIRONMENT"), "production"),
	}
}

// AdminCallbackURL is the absolute URL the BFF registers as its identity in
// every /oauth2/authorize call and every /oauth2/token exchange
// (redirect_uri must match byte-for-byte on both). It MUST equal the
// backend's closeauth.bff.admin-callback (docker-profile env
// BFF_ADMIN_CALLBACK) — see the startup log line in server.go. A mismatch
// (trailing slash, "127.0.0.1" vs "localhost", wrong port) fails token
// exchange with an opaque invalid_grant, so this is the single most likely
// misconfiguration in this stage.
func (c *BFFConfig) AdminCallbackURL() string {
	return c.BaseURL + c.AdminCallbackPath
}

// AdminClientID returns the deterministic per-tenant admin-console client id
// for slug, matching AdminConsoleClientProvisioningCallback's
// CLIENT_ID_PREFIX + tenant.getSlug() on the backend exactly.
func (c *BFFConfig) AdminClientID(slug string) string {
	return c.AdminClientIDPrefix + slug
}

// Validate checks the BFF configuration is usable. Unlike the pre-existing
// three Validate() methods in this package (ServerConfig, BackendConfig,
// MiddlewareConfig), all of which are loaded but never called, this one IS
// called from server.go's NewServer — a bad admin-console config should fail
// loudly at startup rather than surface later as opaque OAuth errors.
func (c *BFFConfig) Validate() error {
	if c.BaseURL == "" {
		return fmt.Errorf("BFF base URL must not be empty")
	}
	if !strings.HasPrefix(c.AdminCallbackPath, "/") {
		return fmt.Errorf("BFF admin callback path must start with '/', got %q", c.AdminCallbackPath)
	}
	if c.AdminClientIDPrefix == "" {
		return fmt.Errorf("BFF admin client id prefix must not be empty")
	}
	if c.AdminScope == "" {
		return fmt.Errorf("BFF admin scope must not be empty")
	}
	if c.SessionMaxAge <= 0 {
		return fmt.Errorf("BFF admin session max age must be positive, got %v", c.SessionMaxAge)
	}
	if c.OAuthContextTTL <= 0 {
		return fmt.Errorf("BFF oauth context TTL must be positive, got %v", c.OAuthContextTTL)
	}
	if c.ReauthSkew < 0 {
		return fmt.Errorf("BFF reauth skew must be non-negative, got %v", c.ReauthSkew)
	}
	return nil
}
