package middleware

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

const SessionCookieName = "bff_session"

// Session represents the authenticated user's session data stored in an encrypted cookie.
type Session struct {
	UserID      string `json:"user_id,omitempty"`
	Email       string `json:"email"`
	Username    string `json:"username,omitempty"`
	Role        string `json:"role,omitempty"`
	AccessToken string `json:"access_token"` // User JWT for X-User-Token forwarding
	ExpiresAt   int64  `json:"expires_at"`   // Unix timestamp
	CreatedAt   int64  `json:"created_at"`   // Unix timestamp
}

// IsExpired returns true if the session has expired.
func (s *Session) IsExpired() bool {
	return time.Now().Unix() > s.ExpiresAt
}

// SetSession encrypts and stores the session in an httpOnly cookie.
func SetSession(w http.ResponseWriter, session *Session, isProduction bool) error {
	session.CreatedAt = time.Now().Unix()

	jsonData, err := json.Marshal(session)
	if err != nil {
		return fmt.Errorf("marshal session: %w", err)
	}

	encrypted, err := Encrypt(jsonData, GetEncryptionKey())
	if err != nil {
		return fmt.Errorf("encrypt session: %w", err)
	}

	encoded := base64.StdEncoding.EncodeToString(encrypted)

	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    encoded,
		Path:     "/",
		MaxAge:   86400, // 24 hours
		HttpOnly: true,
		Secure:   isProduction,
		SameSite: http.SameSiteLaxMode,
	})

	return nil
}

// GetSession reads and decrypts the session from the request cookie.
func GetSession(r *http.Request) (*Session, error) {
	cookie, err := r.Cookie(SessionCookieName)
	if err != nil {
		return nil, fmt.Errorf("session cookie not found: %w", err)
	}

	encrypted, err := base64.StdEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decode session cookie: %w", err)
	}

	decrypted, err := Decrypt(encrypted, GetEncryptionKey())
	if err != nil {
		return nil, fmt.Errorf("decrypt session: %w", err)
	}

	var session Session
	if err := json.Unmarshal(decrypted, &session); err != nil {
		return nil, fmt.Errorf("unmarshal session: %w", err)
	}

	return &session, nil
}

// ClearSession removes the session cookie.
func ClearSession(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
	})
}
