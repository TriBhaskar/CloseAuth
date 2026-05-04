package middleware

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

const OAuthContextCookieName = "oauth_context"

// OAuthContext stores OAuth2 authorization request parameters in an encrypted cookie.
// This preserves the OAuth flow state across the login/consent pages.
type OAuthContext struct {
	ResponseType    string `json:"response_type"`
	ClientID        string `json:"client_id"`
	RedirectURI     string `json:"redirect_uri"`
	Scope           string `json:"scope"`
	State           string `json:"state"`
	Timestamp       int64  `json:"timestamp"`
	SpringSessionID string `json:"spring_session_id,omitempty"` // JSESSIONID for session continuity
	Username        string `json:"username,omitempty"`          // Set after login
}

// SaveOAuthContext encrypts and stores the OAuth context in a cookie (600s TTL).
func SaveOAuthContext(w http.ResponseWriter, ctx *OAuthContext, isProduction bool) error {
	ctx.Timestamp = time.Now().Unix()

	jsonData, err := json.Marshal(ctx)
	if err != nil {
		return fmt.Errorf("marshal oauth context: %w", err)
	}

	encrypted, err := Encrypt(jsonData, GetEncryptionKey())
	if err != nil {
		return fmt.Errorf("encrypt oauth context: %w", err)
	}

	encoded := base64.StdEncoding.EncodeToString(encrypted)

	http.SetCookie(w, &http.Cookie{
		Name:     OAuthContextCookieName,
		Value:    encoded,
		Path:     "/",
		MaxAge:   600, // 10 minutes
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})

	return nil
}

// GetOAuthContext reads and decrypts the OAuth context from the request cookie.
func GetOAuthContext(r *http.Request) (*OAuthContext, error) {
	cookie, err := r.Cookie(OAuthContextCookieName)
	if err != nil {
		return nil, fmt.Errorf("oauth context cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode oauth context cookie: %w", err)
	}

	decrypted, err := Decrypt(encrypted, GetEncryptionKey())
	if err != nil {
		return nil, fmt.Errorf("decrypt oauth context: %w", err)
	}

	var ctx OAuthContext
	if err := json.Unmarshal(decrypted, &ctx); err != nil {
		return nil, fmt.Errorf("unmarshal oauth context: %w", err)
	}

	// Check expiration (10 minutes)
	if time.Now().Unix()-ctx.Timestamp > 600 {
		return nil, fmt.Errorf("oauth context expired")
	}

	return &ctx, nil
}

// ClearOAuthContext removes the OAuth context cookie.
func ClearOAuthContext(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     OAuthContextCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
	})
}
