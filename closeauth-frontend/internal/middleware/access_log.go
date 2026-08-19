package middleware

import (
	"net/http"
	"strings"

	chimw "github.com/go-chi/chi/v5/middleware"
)

// AccessLogger wraps chi's default request logger, skipping static asset
// requests (everything the SPA build serves under /assets/ — JS/CSS/font
// chunks, per internal/static/embed.go). Found during manual testing: every
// SPA page load produces a burst of asset-fetch log lines that bury the one
// request actually worth reading (the real API/navigation hit). Assets have
// no request-scoped behavior worth observing — this is a pure log-volume
// fix, not a security or correctness change.
func AccessLogger(next http.Handler) http.Handler {
	logged := chimw.Logger(next)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/assets/") {
			next.ServeHTTP(w, r)
			return
		}
		logged.ServeHTTP(w, r)
	})
}
