package middleware

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
)

const (
	CSRFTokenLength = 32
	CSRFCookieName  = "csrf_token"
	CSRFHeaderName  = "X-CSRF-Token"
	CSRFFormField   = "csrf_token"
)

// CSRFTokenMiddleware generates a CSRF token and sets it as an httpOnly cookie
// on every request. The token is available server-side for validation.
func CSRFTokenMiddleware(isProduction bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Check if token already exists in cookie
			if _, err := r.Cookie(CSRFCookieName); err != nil {
				// Generate new token
				token, err := generateCSRFToken()
				if err != nil {
					http.Error(w, "Internal server error", http.StatusInternalServerError)
					return
				}

				http.SetCookie(w, &http.Cookie{
					Name:     CSRFCookieName,
					Value:    token,
					Path:     "/",
					HttpOnly: true,
					Secure:   isProduction,
					SameSite: http.SameSiteLaxMode,
				})
			}

			next.ServeHTTP(w, r)
		})
	}
}

// CSRFValidationMiddleware validates the CSRF token on state-changing requests (POST/PUT/DELETE).
// Accepts the token from either the X-CSRF-Token header (SPA fetch) or csrf_token form field (native forms).
//
// Stage UI-3a mounts this ONLY on the JSON-only /t/{slug}/api route group —
// never on Surface-1's relay routes (handlers_auth_proxy.go documents why
// those are deliberately unprotected: no ambient session-cookie
// authentication for CSRF to defend). That placement is itself a safeguard
// for the fix below, not a substitute for it.
func CSRFValidationMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Only validate on state-changing methods
		if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
			next.ServeHTTP(w, r)
			return
		}

		// Get expected token from cookie
		cookie, err := r.Cookie(CSRFCookieName)
		if err != nil {
			http.Error(w, `{"error": "CSRF token missing"}`, http.StatusForbidden)
			return
		}
		expectedToken := cookie.Value

		// Get submitted token from header OR form field
		submittedToken := r.Header.Get(CSRFHeaderName)
		if submittedToken == "" {
			// Try a form field (for native form POSTs, e.g. the consent
			// page), but ONLY when the body actually IS a form — r.ParseForm()
			// drains r.Body, which would silently empty a JSON body or a
			// relayed body handed onward via proxy.Relay/handlers_login_json.go's
			// synthetic upstream request. Every admin-console JSON endpoint
			// this middleware guards sends the token via the header, so this
			// fallback exists purely for form-encoded callers and must not
			// fire for anything else.
			contentType := r.Header.Get("Content-Type")
			if strings.HasPrefix(contentType, "application/x-www-form-urlencoded") ||
				strings.HasPrefix(contentType, "multipart/form-data") {
				if err := r.ParseForm(); err == nil {
					submittedToken = r.FormValue(CSRFFormField)
				}
			}
		}

		if submittedToken == "" {
			http.Error(w, `{"error": "CSRF token not provided"}`, http.StatusForbidden)
			return
		}

		// Constant-time comparison to prevent timing attacks
		if subtle.ConstantTimeCompare([]byte(expectedToken), []byte(submittedToken)) != 1 {
			http.Error(w, `{"error": "CSRF token invalid"}`, http.StatusForbidden)
			return
		}

		next.ServeHTTP(w, r)
	})
}

// HandleCSRFToken is the handler for GET /api/csrf, returning the CSRF token
// as JSON so the SPA can include it in fetch headers (src/api/client.ts's
// fetchCsrfToken already calls this path — before stage UI-3a wired a real
// route here it 404ed and the SPA silently proceeded without a token).
//
// Takes isProduction explicitly (matching CSRFTokenMiddleware) rather than
// the old hardcoded Secure:false, whose comment incorrectly claimed
// "middleware" would override it on production — nothing did, since this
// mint-on-miss branch is the only writer for a first-time visitor.
func HandleCSRFToken(isProduction bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Read current token from cookie (set by middleware)
		cookie, err := r.Cookie(CSRFCookieName)
		if err != nil {
			// Generate a new token if none exists
			token, err := generateCSRFToken()
			if err != nil {
				http.Error(w, `{"error": "Failed to generate CSRF token"}`, http.StatusInternalServerError)
				return
			}

			// Set cookie for future validation
			http.SetCookie(w, &http.Cookie{
				Name:     CSRFCookieName,
				Value:    token,
				Path:     "/",
				HttpOnly: true,
				Secure:   isProduction,
				SameSite: http.SameSiteLaxMode,
			})

			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]string{"token": token})
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"token": cookie.Value})
	}
}

// ClearCSRFToken removes the CSRF cookie (used during logout/sign-out).
func ClearCSRFToken(w http.ResponseWriter, isProduction bool) {
	clearCookie(w, CSRFCookieName, "/", isProduction)
}

// generateCSRFToken generates a cryptographically secure random token.
func generateCSRFToken() (string, error) {
	bytes := make([]byte, CSRFTokenLength)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return base64.URLEncoding.EncodeToString(bytes), nil
}
