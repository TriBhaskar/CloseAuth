package middleware

import (
	"net/http"
)

// NoCacheMiddleware adds headers to prevent browser caching
// This is critical for authenticated pages to prevent back button issues
func NoCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Set no-cache headers
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
		
		next.ServeHTTP(w, r)
	})
}
