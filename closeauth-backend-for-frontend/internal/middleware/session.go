package middleware

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

const (
	SessionCookieName = "bff_session"
)

// Session represents the user session data stored in an encrypted cookie
type Session struct {
	UserID       string `json:"user_id,omitempty"`
	Email        string `json:"email"`
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token,omitempty"`
	ExpiresAt    int64  `json:"expires_at"` // Unix timestamp when the session expires
	CreatedAt    int64  `json:"created_at"` // Unix timestamp when session was created
}

// IsExpired checks if the session has expired
func (s *Session) IsExpired() bool {
	return time.Now().Unix() > s.ExpiresAt
}

// TimeUntilExpiry returns the duration until the session expires
func (s *Session) TimeUntilExpiry() time.Duration {
	return time.Until(time.Unix(s.ExpiresAt, 0))
}

// SetSession encrypts and saves the session to a cookie
func SetSession(w http.ResponseWriter, session *Session) error {
	session.CreatedAt = time.Now().Unix()

	// Marshal session to JSON
	jsonData, err := json.Marshal(session)
	if err != nil {
		return fmt.Errorf("failed to marshal session: %w", err)
	}

	// Encrypt the JSON data using the same encryption as OAuth context
	encryptedData, err := encrypt(jsonData, GetEncryptionKey())
	if err != nil {
		return fmt.Errorf("failed to encrypt session: %w", err)
	}

	// Encode to base64 for cookie storage
	encodedData := base64.URLEncoding.EncodeToString(encryptedData)

	// Calculate max age based on token expiry
	maxAge := int(session.ExpiresAt - time.Now().Unix())
	if maxAge < 0 {
		maxAge = 0
	}

	// Set cookie
	cookie := &http.Cookie{
		Name:     SessionCookieName,
		Value:    encodedData,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   isProductionEnv(),
		SameSite: http.SameSiteLaxMode,
	}
	http.SetCookie(w, cookie)

	return nil
}

// GetSession retrieves and decrypts the session from the cookie
func GetSession(r *http.Request) (*Session, error) {
	cookie, err := r.Cookie(SessionCookieName)
	if err != nil {
		return nil, fmt.Errorf("session cookie not found: %w", err)
	}

	// Decode from base64
	encryptedData, err := base64.URLEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("failed to decode session: %w", err)
	}

	// Decrypt the data
	jsonData, err := decrypt(encryptedData, GetEncryptionKey())
	if err != nil {
		return nil, fmt.Errorf("failed to decrypt session: %w", err)
	}

	// Unmarshal JSON
	var session Session
	if err := json.Unmarshal(jsonData, &session); err != nil {
		return nil, fmt.Errorf("failed to unmarshal session: %w", err)
	}

	return &session, nil
}

// GetValidSession retrieves the session and validates it hasn't expired
func GetValidSession(r *http.Request) (*Session, error) {
	session, err := GetSession(r)
	if err != nil {
		return nil, err
	}

	if session.IsExpired() {
		return nil, fmt.Errorf("session has expired")
	}

	return session, nil
}

// ClearSession removes the session cookie
func ClearSession(w http.ResponseWriter) {
	cookie := &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   isProductionEnv(),
		SameSite: http.SameSiteLaxMode,
	}
	http.SetCookie(w, cookie)
}

// Session context key for request context
type sessionContextKey struct{}

// SessionContextKey is the key used to store session in request context
var SessionContextKey = sessionContextKey{}

// GetSessionFromContext retrieves the session from request context
func GetSessionFromContext(r *http.Request) *Session {
	if session, ok := r.Context().Value(SessionContextKey).(*Session); ok {
		return session
	}
	return nil
}
