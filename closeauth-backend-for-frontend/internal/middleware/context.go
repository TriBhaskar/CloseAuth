package middleware

import (
	"context"
	"net/http"
)

type contextKey string

const (
	CSRFTokenKey contextKey = "csrf_token"
)

// CSRFTokenMiddleware adds CSRF token to request context for templates
func CSRFTokenMiddleware(config CSRFConfig) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, err := GetCSRFToken(r, config)
			if err != nil {
				// Generate new token if none exists
				token, err = generateCSRFToken(config.TokenLength)
				if err != nil {
					http.Error(w, "Failed to generate CSRF token", http.StatusInternalServerError)
					return
				}
				SetCSRFToken(w, token, config)
			}

			// Add token to context
			ctx := context.WithValue(r.Context(), CSRFTokenKey, token)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// GetCSRFTokenFromContext retrieves CSRF token from context
func GetCSRFTokenFromContext(ctx context.Context) string {
	if token, ok := ctx.Value(CSRFTokenKey).(string); ok {
		return token
	}
	return ""
}