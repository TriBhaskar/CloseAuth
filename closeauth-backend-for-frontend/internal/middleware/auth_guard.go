package middleware

import (
	"closeauth-backend-for-frontend/internal/constants"
	"context"
	"net/http"
)

// RequireAuth is a middleware that checks if the user has a valid session
// If not, it redirects to the login page
func RequireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, err := GetValidSession(r)
		if err != nil {
			// Session not found or expired - redirect to login
			handleAuthRedirect(w, r)
			return
		}

		// Add session to request context for use in handlers
		ctx := context.WithValue(r.Context(), SessionContextKey, session)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// RequireAuthFunc is a middleware function version for use with chi
func RequireAuthFunc(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, err := GetValidSession(r)
		if err != nil {
			// Session not found or expired - redirect to login
			handleAuthRedirect(w, r)
			return
		}

		// Add session to request context for use in handlers
		ctx := context.WithValue(r.Context(), SessionContextKey, session)
		next.ServeHTTP(w, r.WithContext(ctx))
	}
}

// handleAuthRedirect redirects to login page, supporting both HTMX and regular requests
func handleAuthRedirect(w http.ResponseWriter, r *http.Request) {
	// Clear any expired session cookie
	ClearSession(w)

	loginURL := constants.RouteAdminLogin

	if IsHTMXRequest(r) {
		// For HTMX requests, use HX-Redirect header
		HTMXRedirect(w, loginURL)
	} else {
		// For regular requests, use standard HTTP redirect
		http.Redirect(w, r, loginURL, http.StatusSeeOther)
	}
}
