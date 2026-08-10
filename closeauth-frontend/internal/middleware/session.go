package middleware

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// AdminSessionCookieName is the tenant-admin console's session cookie name.
// It is deliberately NOT slug-parameterized like OAuthContextCookieName:
// isolation between tenants comes from the cookie's PATH
// (AdminSessionCookiePath), not its name, so /t/acme and /t/globex sessions
// coexist as two same-named cookies with different Path attributes — one
// browser can hold both simultaneously (RFC 6265 §5.1.4 path matching).
const AdminSessionCookieName = "bff_admin_session"

// AdminSessionCookiePath returns the Path every admin session cookie
// operation for slug uses. A request path outside "/t/{slug}" never
// receives this cookie — in particular Surface-1's relay routes (all at
// Path=/) never see it, so it can never accidentally reach the backend via
// proxy.Relay.
func AdminSessionCookiePath(slug string) string {
	return "/t/" + slug
}

func adminSessionAAD(slug string) []byte {
	return []byte("closeauth.bff.admin-session:" + slug)
}

// AdminSession is the tenant-admin console's authenticated session, held
// server-side in an encrypted, path-scoped cookie. The access token never
// reaches browser JS — every field here lives only in this cookie and in
// values derived from it server-side.
//
// This supersedes the old flat Session type (single Role string, no tenant
// context, 24h-flat AccessToken with no expiry tracking) per that type's own
// TODO(ui-1), which explicitly asked for tenant-admin and platform-admin
// sessions to never be conflated under one shape — AdminSession is
// tenant-admin only; a platform-admin console (UI-4) gets its own type.
//
// Distinct from internal/backend.Session (the OAuth-flow RESULT shape
// returned by OAuthClient.Login/Exchange, no JSON tags, not meant to be
// serialized to a cookie): that one is what the flow hands back; this one is
// what the BFF persists from it.
type AdminSession struct {
	Slug        string   `json:"slug"`
	TenantID    string   `json:"tenant_id"`
	UserID      string   `json:"user_id"`
	Email       string   `json:"email"` // backfilled via AdminClient.Me — not a JWT claim
	TenantRoles []string `json:"tenant_roles"`
	// ClientID is the admin-console client this session was issued to
	// (admin-console-{slug}) — the cryptographic slug binding established at
	// the callback's verification triple; kept here so it can be
	// re-asserted on every subsequent read, not just at issuance.
	ClientID       string `json:"client_id"`
	AccessToken    string `json:"access_token"`
	AccessTokenExp int64  `json:"access_token_exp"` // unix seconds
	CreatedAt      int64  `json:"created_at"`       // unix seconds
}

// IsTenantAdmin reports whether the session's tenant_roles include
// TENANT_ADMIN. Authorization is re-checked on every gated request via this
// method (RequireAdminSession), not just once at login — a role revoked
// mid-session is caught on the session's next use, not silently honored
// until the token expires.
func (s *AdminSession) IsTenantAdmin() bool {
	for _, role := range s.TenantRoles {
		if role == "TENANT_ADMIN" {
			return true
		}
	}
	return false
}

// NeedsReauth reports whether the access token is already expired or will
// expire within skew of now. This is the ONLY signal RequireAdminSession
// uses to trigger silent re-authorization — lazy, not timer-driven: a
// request that doesn't need a token never checks this, so nothing
// interrupts a user mid-form.
func (s *AdminSession) NeedsReauth(now time.Time, skew time.Duration) bool {
	return !now.Add(skew).Before(time.Unix(s.AccessTokenExp, 0))
}

// SetAdminSession encrypts and stores session in a cookie scoped to slug's
// path, with the given lifetime (BFFConfig.SessionMaxAge). Stamps CreatedAt.
// Unlike the old SetSession (which stamped CreatedAt but never ExpiresAt,
// silently making every session read back as "already expired"),
// AccessTokenExp must be set by the caller BEFORE calling this — it is not
// derived here — since it comes from the token response, not from maxAge.
func SetAdminSession(w http.ResponseWriter, slug string, session *AdminSession, isProduction bool, maxAge time.Duration) error {
	session.Slug = slug
	session.CreatedAt = time.Now().Unix()

	jsonData, err := json.Marshal(session)
	if err != nil {
		return fmt.Errorf("marshal admin session: %w", err)
	}

	encrypted, err := Seal(jsonData, GetEncryptionKey(), adminSessionAAD(slug))
	if err != nil {
		return fmt.Errorf("seal admin session: %w", err)
	}

	encoded := base64.StdEncoding.EncodeToString(encrypted)

	http.SetCookie(w, &http.Cookie{
		Name:     AdminSessionCookieName,
		Value:    encoded,
		Path:     AdminSessionCookiePath(slug),
		MaxAge:   int(maxAge.Seconds()),
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})

	return nil
}

// GetAdminSession reads, decrypts, and validates the admin session cookie
// for slug. Rejects a session whose own Slug field doesn't match slug
// (belt-and-suspenders alongside the AAD binding).
func GetAdminSession(r *http.Request, slug string) (*AdminSession, error) {
	cookie, err := r.Cookie(AdminSessionCookieName)
	if err != nil {
		return nil, fmt.Errorf("admin session cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode admin session cookie: %w", err)
	}

	decrypted, err := Open(encrypted, GetEncryptionKey(), adminSessionAAD(slug))
	if err != nil {
		return nil, fmt.Errorf("open admin session: %w", err)
	}

	var session AdminSession
	if err := json.Unmarshal(decrypted, &session); err != nil {
		return nil, fmt.Errorf("unmarshal admin session: %w", err)
	}

	if session.Slug != slug {
		return nil, fmt.Errorf("admin session slug mismatch: cookie=%q want=%q", session.Slug, slug)
	}

	return &session, nil
}

// ClearAdminSession removes the admin session cookie for slug.
func ClearAdminSession(w http.ResponseWriter, slug string, isProduction bool) {
	clearCookie(w, AdminSessionCookieName, AdminSessionCookiePath(slug), isProduction)
}

// --- Denial marker -----------------------------------------------------
//
// AdminDenied breaks the "non-admin, revisited" login loop (see stage
// report / plan): without it, a second visit to /t/{slug}/admin/login by an
// authenticated-but-non-admin user would silently re-run the whole
// authorize round trip and land on /denied again, forever, since SSO
// recognizes the same non-admin identity every time. This short-lived
// marker cookie, set at the moment a callback's verification triple fails,
// makes /t/{slug}/admin/{login,reauth} short-circuit straight to /denied
// WITHOUT touching /oauth2/authorize on a repeat visit. Escaping requires an
// explicit user action (POST .../denied/dismiss) that clears it.

// AdminDeniedCookieName is the tenant-admin console's denial-marker cookie
// name, path-scoped like AdminSessionCookieName.
const AdminDeniedCookieName = "bff_admin_denied"

// AdminDeniedMaxAge bounds how long a denial marker survives — long enough
// to prevent an immediate loop, short enough that it doesn't outlive the
// user's actual browsing session.
const AdminDeniedMaxAge = 5 * time.Minute

func adminDeniedAAD(slug string) []byte {
	return []byte("closeauth.bff.admin-denied:" + slug)
}

// AdminDenied records why an authenticated user was refused admin-console
// access to a tenant.
type AdminDenied struct {
	Slug      string `json:"slug"`
	Reason    string `json:"reason"`
	Sub       string `json:"sub,omitempty"` // the refused user's sub claim, for diagnostics only
	Timestamp int64  `json:"timestamp"`
}

// SetAdminDenied sets the denial marker for slug.
func SetAdminDenied(w http.ResponseWriter, slug, reason, sub string, isProduction bool) error {
	marker := AdminDenied{Slug: slug, Reason: reason, Sub: sub, Timestamp: time.Now().Unix()}

	jsonData, err := json.Marshal(marker)
	if err != nil {
		return fmt.Errorf("marshal admin denied marker: %w", err)
	}

	encrypted, err := Seal(jsonData, GetEncryptionKey(), adminDeniedAAD(slug))
	if err != nil {
		return fmt.Errorf("seal admin denied marker: %w", err)
	}

	http.SetCookie(w, &http.Cookie{
		Name:     AdminDeniedCookieName,
		Value:    base64.StdEncoding.EncodeToString(encrypted),
		Path:     AdminSessionCookiePath(slug),
		MaxAge:   int(AdminDeniedMaxAge.Seconds()),
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})
	return nil
}

// GetAdminDenied reads the denial marker for slug, if present and valid.
func GetAdminDenied(r *http.Request, slug string) (*AdminDenied, error) {
	cookie, err := r.Cookie(AdminDeniedCookieName)
	if err != nil {
		return nil, fmt.Errorf("admin denied cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode admin denied cookie: %w", err)
	}

	decrypted, err := Open(encrypted, GetEncryptionKey(), adminDeniedAAD(slug))
	if err != nil {
		return nil, fmt.Errorf("open admin denied marker: %w", err)
	}

	var marker AdminDenied
	if err := json.Unmarshal(decrypted, &marker); err != nil {
		return nil, fmt.Errorf("unmarshal admin denied marker: %w", err)
	}

	if marker.Slug != slug {
		return nil, fmt.Errorf("admin denied slug mismatch: cookie=%q want=%q", marker.Slug, slug)
	}

	return &marker, nil
}

// ClearAdminDenied removes the denial marker for slug — the effect of the
// user's explicit "try a different account" action.
func ClearAdminDenied(w http.ResponseWriter, slug string, isProduction bool) {
	clearCookie(w, AdminDeniedCookieName, AdminSessionCookiePath(slug), isProduction)
}
