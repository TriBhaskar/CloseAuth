package middleware

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"net/http"
)

const (
	CSRFTokenLength = 32
	CSRFCookieName  = "csrf_token"
	CSRFHeaderName  = "X-CSRF-Token"
	CSRFFormField   = "csrf_token"
)

// CSRFConfig holds CSRF middleware configuration
type CSRFConfig struct {
	TokenLength  int
	CookieName   string
	HeaderName   string
	FormField    string
	CookiePath   string
	CookieDomain string
	Secure       bool
	HttpOnly     bool
	SameSite     http.SameSite
}

// DefaultCSRFConfig returns default CSRF configuration
func DefaultCSRFConfig() CSRFConfig {
	return CSRFConfig{
		TokenLength:  CSRFTokenLength,
		CookieName:   CSRFCookieName,
		HeaderName:   CSRFHeaderName,
		FormField:    CSRFFormField,
		CookiePath:   "/",
		CookieDomain: "",
		Secure:       false, // Set to true in production with HTTPS
		HttpOnly:     true,
		SameSite:     http.SameSiteLaxMode,
	}
}

// generateCSRFToken generates a random CSRF token
func generateCSRFToken(length int) (string, error) {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return base64.URLEncoding.EncodeToString(bytes), nil
}

// CSRFMiddleware creates CSRF protection middleware
func CSRFMiddleware(config CSRFConfig) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Skip CSRF for safe methods
			if r.Method == "GET" || r.Method == "HEAD" || r.Method == "OPTIONS" {
				// For GET requests, ensure we have a CSRF token
				if _, err := GetCSRFToken(r, config); err != nil {
					// Generate new token if none exists
					token, err := generateCSRFToken(config.TokenLength)
					if err != nil {
						http.Error(w, "Failed to generate CSRF token", http.StatusInternalServerError)
						return
					}
					SetCSRFToken(w, token, config)
				}
				next.ServeHTTP(w, r)
				return
			}

			// For unsafe methods, validate CSRF token
			expectedToken, err := GetCSRFToken(r, config)
			if err != nil {
				http.Error(w, "CSRF token not found", http.StatusForbidden)
				return
			}

			// Get token from request (header or form)
			var actualToken string
			
			// First try header
			actualToken = r.Header.Get(config.HeaderName)
			
			// If not in header, try form field
			if actualToken == "" {
				if err := r.ParseForm(); err == nil {
					actualToken = r.FormValue(config.FormField)
				}
			}

			// Validate token
			if !validateCSRFToken(expectedToken, actualToken) {
				http.Error(w, "CSRF token mismatch", http.StatusForbidden)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

// GetCSRFToken retrieves CSRF token from cookie
func GetCSRFToken(r *http.Request, config CSRFConfig) (string, error) {
	cookie, err := r.Cookie(config.CookieName)
	if err != nil {
		return "", err
	}
	return cookie.Value, nil
}

// SetCSRFToken sets CSRF token in cookie
func SetCSRFToken(w http.ResponseWriter, token string, config CSRFConfig) {
	cookie := NewSecureCookieWithOptions(CookieOptions{
		Name:     config.CookieName,
		Value:    token,
		Path:     config.CookiePath,
		MaxAge:   86400, // 24 hours in seconds
		HttpOnly: config.HttpOnly,
		Secure:   config.Secure,
		SameSite: config.SameSite,
	})
	http.SetCookie(w, cookie)
}

// validateCSRFToken validates CSRF token using constant-time comparison
func validateCSRFToken(expected, actual string) bool {
	if len(expected) != len(actual) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(expected), []byte(actual)) == 1
}

// GetCSRFTokenFromRequest is a helper to get CSRF token for templates
func GetCSRFTokenFromRequest(r *http.Request) string {
	config := DefaultCSRFConfig()
	token, err := GetCSRFToken(r, config)
	if err != nil {
		// Generate a new token if none exists
		newToken, err := generateCSRFToken(config.TokenLength)
		if err != nil {
			return ""
		}
		return newToken
	}
	return token
}

// CSRFFailureHandler handles CSRF validation failures
func CSRFFailureHandler(w http.ResponseWriter, r *http.Request, reason string) {
	// Check if it's an HTMX request
	if IsHTMXRequest(r) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(fmt.Sprintf("CSRF validation failed: %s", reason)))
		return
	}

	// For regular requests, redirect to error page or show error
	http.Error(w, fmt.Sprintf("CSRF validation failed: %s", reason), http.StatusForbidden)
}