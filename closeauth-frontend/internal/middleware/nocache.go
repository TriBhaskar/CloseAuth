package middleware

import "net/http"

func NoCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0")
		w.Header().Add("Pragma", "no-cache")
		w.Header().Add("Expires", "0")

		next.ServeHTTP(w, r)
	})
}
