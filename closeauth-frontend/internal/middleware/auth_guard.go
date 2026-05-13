package middleware

import (
	"encoding/json"
	"net/http"
)

// RequireAuth is a middleware that checks for a valid session.
// Returns 401 JSON on failure (SPA handles this client-side).
func RequireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, err := GetSession(r)
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]string{"error": "unauthorized"})
			return
		}

		if session.IsExpired() {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]string{"error": "session expired"})
			return
		}

		next.ServeHTTP(w, r)
	})
}
