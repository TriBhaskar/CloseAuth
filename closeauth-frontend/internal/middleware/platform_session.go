package middleware

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// Stage UI-4: the platform-admin console's own session type — deliberately
// NOT AdminSession with a blank slug. Platform admins are a separate
// principal type (platform_admins table, Stage 7a), not a role on a tenant
// user, and the token this session wraps is structurally different in ways
// that ripple through every cookie decision below:
//
//   - No slug ANYWHERE (cookie name, path, AAD). This surface is
//     cross-tenant by nature; a slug field would be a lie. Contrast
//     AdminSession, whose isolation between tenants comes from the cookie's
//     PATH — there is nothing to isolate here.
//   - The backend token is access-only, 5-minute TTL, NO refresh
//     (PlatformAdminTokenService.mintFor / properties.getPlatformAdmin().
//     getTokenTtl()). There is no silent-reauth mechanism and there
//     shouldn't be one — AdminSession.NeedsReauth/ReauthSkew have no
//     analogue here; RequirePlatformSession (platform_guard.go) fails closed
//     on expiry instead of triggering anything.
//   - The cookie's own MaxAge is derived from the token's expires_in at
//     SetPlatformSession time, not from a BFFConfig knob — the cookie should
//     die exactly when the credential it holds dies, never outlive it.

// PlatformSessionCookieName is the platform-admin console's session cookie.
const PlatformSessionCookieName = "bff_platform_session"

// PlatformSessionCookiePath scopes the cookie to the platform console's own
// route tree — disjoint from AdminSessionCookiePath's "/t/{slug}" family, so
// a browser holding both a tenant-admin and a platform-admin session never
// presents one where the other is expected.
const PlatformSessionCookiePath = "/platform"

func platformSessionAAD() []byte {
	return []byte("closeauth.bff.platform-session")
}

// PlatformSession is the platform-admin console's authenticated session,
// held server-side in an encrypted, path-scoped cookie. The access token
// never reaches browser JS — every field here lives only in this cookie and
// in values derived from it server-side.
type PlatformSession struct {
	AdminID string   `json:"admin_id"`
	Email   string   `json:"email"`
	Roles   []string `json:"roles"`
	// AccessToken is the platform-admin bearer token minted by
	// POST /v1/platform/auth/token. Never serialized to the browser — only
	// ever read server-side to call s.adminClient.
	AccessToken    string `json:"access_token"`
	AccessTokenExp int64  `json:"access_token_exp"` // unix seconds
	CreatedAt      int64  `json:"created_at"`       // unix seconds
}

// IsPlatformAdmin reports whether the session's roles include PLATFORM_ADMIN
// — the exact role @RequiresPlatformAdmin checks on the backend. A newly
// created platform admin has no roles by default (creation alone doesn't
// grant access), so this is re-checked on every gated request, not just at
// login: a role revoked mid-session is caught on the session's next use.
func (s *PlatformSession) IsPlatformAdmin() bool {
	for _, role := range s.Roles {
		if role == "PLATFORM_ADMIN" {
			return true
		}
	}
	return false
}

// Expired reports whether the access token is already past its expiry.
// Deliberately no skew/buffer (unlike AdminSession.NeedsReauth): there is no
// silent-renewal path to pre-empt expiry for, so "expired" just means
// "expired" — RequirePlatformSession fails closed the moment this is true.
func (s *PlatformSession) Expired(now time.Time) bool {
	return !now.Before(time.Unix(s.AccessTokenExp, 0))
}

// SetPlatformSession encrypts and stores session in a cookie whose MaxAge is
// derived from the session's own AccessTokenExp — the cookie expires exactly
// when the 5-minute credential it holds does, never later. Stamps CreatedAt.
func SetPlatformSession(w http.ResponseWriter, session *PlatformSession, isProduction bool) error {
	session.CreatedAt = time.Now().Unix()

	jsonData, err := json.Marshal(session)
	if err != nil {
		return fmt.Errorf("marshal platform session: %w", err)
	}

	encrypted, err := Seal(jsonData, GetEncryptionKey(), platformSessionAAD())
	if err != nil {
		return fmt.Errorf("seal platform session: %w", err)
	}

	encoded := base64.StdEncoding.EncodeToString(encrypted)

	maxAge := time.Until(time.Unix(session.AccessTokenExp, 0))
	if maxAge < 0 {
		maxAge = 0
	}

	http.SetCookie(w, &http.Cookie{
		Name:     PlatformSessionCookieName,
		Value:    encoded,
		Path:     PlatformSessionCookiePath,
		MaxAge:   int(maxAge.Seconds()),
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})

	return nil
}

// GetPlatformSession reads and decrypts the platform session cookie.
func GetPlatformSession(r *http.Request) (*PlatformSession, error) {
	cookie, err := r.Cookie(PlatformSessionCookieName)
	if err != nil {
		return nil, fmt.Errorf("platform session cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode platform session cookie: %w", err)
	}

	decrypted, err := Open(encrypted, GetEncryptionKey(), platformSessionAAD())
	if err != nil {
		return nil, fmt.Errorf("open platform session: %w", err)
	}

	var session PlatformSession
	if err := json.Unmarshal(decrypted, &session); err != nil {
		return nil, fmt.Errorf("unmarshal platform session: %w", err)
	}

	return &session, nil
}

// ClearPlatformSession removes the platform session cookie.
func ClearPlatformSession(w http.ResponseWriter, isProduction bool) {
	clearCookie(w, PlatformSessionCookieName, PlatformSessionCookiePath, isProduction)
}
