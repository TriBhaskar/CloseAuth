package backend

import "time"

// Principal type discriminators. A CloseAuth JWT never carries an explicit
// "this is a tenant user" marker — the tenant-user shape is inferred from the
// PRESENCE of a tenant_id claim, and the platform-admin shape from its ABSENCE
// plus an explicit token_use=platform_admin claim (docs/backend/00_OVERVIEW.md
// "Principal / token types"). These constants name that inference so callers
// never compare against the raw claim strings directly.
const (
	PrincipalTypeTenantUser    = "tenant_user"
	PrincipalTypePlatformAdmin = "platform_admin"
)

// Session is the BFF's own record of a logged-in Surface 2/3 (admin console)
// principal — NOT used for Surface 1 (hosted end-user auth pages), which is a
// pure relay with no BFF-side session state at all (the backend's own
// CLOSEAUTH_SESSION cookie is the entire session there). This type exists now
// because the OAuth2 flow client (Deliverable 2) needs a shape to hand tokens
// back in; nothing in this stage stores it in a cookie yet (that's UI-3's
// wiring once Surfaces 2/3 have routes).
//
// Field-by-field provenance, confirmed against docs/backend/00_OVERVIEW.md
// ("Principal / token types") and CloseAuthTokenCustomizer /
// PlatformAdminTokenService (closeauth-backend/src/main/java/.../token,
// .../platform/service):
//
//   - PrincipalType: derived, not a literal claim — tenant_user when the
//     access token carries a tenant_id claim, platform_admin when it carries
//     token_use=platform_admin (and no tenant_id).
//   - UserID: the "sub" claim (user UUID or platform-admin UUID).
//   - Email: NOT a JWT claim on either token shape (confirmed: neither
//     CloseAuthTokenCustomizer nor PlatformAdminTokenService stamps an email
//     claim). Callers must fetch it separately via GET /v1/me after token
//     exchange — see AdminClient.Me. This is a deviation from the prompt's
//     literal Session sketch, documented in the stage report.
//   - TenantID: the "tenant_id" claim; empty for platform_admin (its absence
//     IS the platform-level signal, never a claim to zero out defensively).
//   - TenantRoles: the "tenant_roles" claim (tenant user only).
//   - PlatformRoles: the "roles" claim. Present on BOTH shapes: a tenant
//     user's token also carries "roles" for platform roles (usually empty
//     per the docs), and a platform-admin token's "roles" IS its platform
//     roles. There is no separate "platform_roles" claim name.
//   - RefreshToken: genuinely optional. Platform-admin tokens never have one
//     (PlatformAdminTokenService mints access-only, by design — shortest-
//     lived, highest-privilege, nothing to steal-and-renew). Tenant-user
//     tokens have one only when minted via a confidential client (SAS does
//     not issue refresh tokens to public clients).
type Session struct {
	PrincipalType  string
	UserID         string
	Email          string
	TenantID       string
	TenantRoles    []string
	PlatformRoles  []string
	AccessToken    string
	AccessTokenExp time.Time
	RefreshToken   string // "" is valid and expected for platform_admin sessions — never assume non-empty.
	CreatedAt      time.Time
}

// Refreshable reports whether this session has a refresh token to rotate.
// Prefer this over a raw `session.RefreshToken != ""` check at call sites so
// the "RefreshToken is optional" invariant has one obvious spelling.
func (s *Session) Refreshable() bool {
	return s.RefreshToken != ""
}
