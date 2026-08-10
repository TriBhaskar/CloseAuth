package middleware

import "net/http"

// NoCacheMiddleware sets cache-prevention headers on the response, using Set
// (not the original Add) so a later relayed response that also sets
// Cache-Control (proxy.CopyHeaders forwards the backend's headers) doesn't
// end up with duplicate Cache-Control values.
func NoCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")

		next.ServeHTTP(w, r)
	})
}
