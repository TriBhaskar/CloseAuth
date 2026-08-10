package middleware

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// oauthContextTTL is the TTL for the oauth_ctx_{slug} cookie in seconds.
// Default: 600 (10 minutes). Updated at startup from BFFConfig.OAuthContextTTL
// via SetOAuthContextTTL — this mechanism predates stage UI-3a and is reused
// as-is; UI-3a is the first caller that actually invokes SetOAuthContextTTL.
var oauthContextTTL int64 = 600

// SetOAuthContextTTL updates the oauth context cookie TTL. Called once at
// startup from server.go, sourced from BFFConfig.OAuthContextTTL.
func SetOAuthContextTTL(ttlSeconds int) {
	if ttlSeconds > 0 {
		oauthContextTTL = int64(ttlSeconds)
	}
}

// OAuthContextCookieName returns the per-tenant cookie name holding a
// pending admin-console login/reauth round trip. Stage UI-3a: this cookie's
// Path is "/" (not "/t/{slug}") because /admin/callback — where it must be
// read — is a single fixed path shared by every tenant (Option A registers
// exactly one redirect_uri per tenant client), so Path-scoping like the
// admin session cookie isn't available here. The cookie NAME is the only
// isolator, hence the slug suffix.
func OAuthContextCookieName(slug string) string {
	return "oauth_ctx_" + slug
}

func oauthContextAAD(slug string) []byte {
	return []byte("closeauth.bff.oauth-ctx:" + slug)
}

// OAuthContext stores one pending admin-console OAuth2 authorization-code +
// PKCE round trip in an encrypted, slug-scoped cookie, from the moment the
// BFF redirects the browser to /oauth2/authorize until /admin/callback
// completes (or the TTL above expires it).
type OAuthContext struct {
	Slug         string `json:"slug"`
	ClientID     string `json:"client_id"`
	RedirectURI  string `json:"redirect_uri"`
	Scope        string `json:"scope"`
	State        string `json:"state"`
	CodeVerifier string `json:"code_verifier"`
	ReturnTo     string `json:"return_to"`
	// Attempt counts how many times this round trip has been (re)started
	// without a successful callback — the pathological-loop guard (a normal
	// login/reauth clears the context on success, so a healthy flow never
	// accumulates attempts). See handlers_admin_auth.go's loop-break logic.
	Attempt   int   `json:"attempt"`
	Timestamp int64 `json:"timestamp"`
}

// SaveOAuthContext encrypts and stores ctx in a cookie scoped to slug. TTL is
// the oauthContextTTL package variable (see SetOAuthContextTTL).
//
// SameSite=Lax is REQUIRED here, not a hardening default: /admin/callback is
// reached via a cross-site top-level GET redirect FROM the backend origin.
// SameSite=Strict would cause the browser to withhold this cookie on that
// navigation, breaking every login — do not "tighten" this.
func SaveOAuthContext(w http.ResponseWriter, slug string, ctx *OAuthContext, isProduction bool) error {
	ctx.Slug = slug
	ctx.Timestamp = time.Now().Unix()

	jsonData, err := json.Marshal(ctx)
	if err != nil {
		return fmt.Errorf("marshal oauth context: %w", err)
	}

	encrypted, err := Seal(jsonData, GetEncryptionKey(), oauthContextAAD(slug))
	if err != nil {
		return fmt.Errorf("seal oauth context: %w", err)
	}

	encoded := base64.StdEncoding.EncodeToString(encrypted)

	http.SetCookie(w, &http.Cookie{
		Name:     OAuthContextCookieName(slug),
		Value:    encoded,
		Path:     "/",
		MaxAge:   int(oauthContextTTL),
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})

	return nil
}

// GetOAuthContext reads, decrypts, and validates the oauth_ctx_{slug} cookie
// for slug. Rejects a context whose own Slug field doesn't match slug (belt
// and suspenders alongside the AAD binding, which would already fail to
// open a different slug's ciphertext) and one older than the TTL.
func GetOAuthContext(r *http.Request, slug string) (*OAuthContext, error) {
	cookie, err := r.Cookie(OAuthContextCookieName(slug))
	if err != nil {
		return nil, fmt.Errorf("oauth context cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode oauth context cookie: %w", err)
	}

	decrypted, err := Open(encrypted, GetEncryptionKey(), oauthContextAAD(slug))
	if err != nil {
		return nil, fmt.Errorf("open oauth context: %w", err)
	}

	var ctx OAuthContext
	if err := json.Unmarshal(decrypted, &ctx); err != nil {
		return nil, fmt.Errorf("unmarshal oauth context: %w", err)
	}

	if ctx.Slug != slug {
		return nil, fmt.Errorf("oauth context slug mismatch: cookie=%q want=%q", ctx.Slug, slug)
	}

	if time.Now().Unix()-ctx.Timestamp > oauthContextTTL {
		return nil, fmt.Errorf("oauth context expired")
	}

	return &ctx, nil
}

// ClearOAuthContext removes the oauth_ctx_{slug} cookie.
func ClearOAuthContext(w http.ResponseWriter, slug string, isProduction bool) {
	clearCookie(w, OAuthContextCookieName(slug), "/", isProduction)
}

// NewState generates a fresh CSRF-style state parameter for one
// /oauth2/authorize call: base64url(slug) + "." + 32 random bytes
// (base64url). The slug prefix is only a ROUTING HINT (SlugFromState) used
// by /admin/callback to pick which oauth_ctx_{slug} cookie to open before
// any cookie has been read — it is NOT the trusted comparison. The trusted
// comparison is StateMatches against the state stored inside that opened,
// authenticated cookie.
func NewState(slug string) (string, error) {
	random := make([]byte, 32)
	if _, err := rand.Read(random); err != nil {
		return "", fmt.Errorf("generate state: %w", err)
	}
	encodedSlug := base64.RawURLEncoding.EncodeToString([]byte(slug))
	return encodedSlug + "." + base64.RawURLEncoding.EncodeToString(random), nil
}

// SlugFromState extracts the routing-hint slug from a state value produced
// by NewState. Returns "" if state isn't in the expected shape — callers
// must treat that as "no usable slug", never as slug "".
func SlugFromState(state string) string {
	prefix, _, found := strings.Cut(state, ".")
	if !found {
		return ""
	}
	slugBytes, err := base64.RawURLEncoding.DecodeString(prefix)
	if err != nil {
		return ""
	}
	return string(slugBytes)
}

// StateMatches compares two state values in constant time. Always use this,
// never ==, when comparing a callback's query-string state against the
// value saved in the oauth context cookie.
func StateMatches(expected, got string) bool {
	return subtle.ConstantTimeCompare([]byte(expected), []byte(got)) == 1
}
