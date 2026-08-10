package static

import (
	"embed"
	"io/fs"
	"net/http"
	"regexp"
	"strings"
)

//go:embed all:dist
var distFS embed.FS

// tenantAPIPath matches the BFF's tenant-admin JSON API surface
// (/t/{slug}/api/... — see routes.go's stage UI-3a route group). It is a
// path pattern check, not a chi route registration: chi's own NotFound
// handler propagates into subrouters declared before it (which is how
// /t/{slug}/console correctly falls through to the SPA below), so this
// package cannot tell "no /t/{slug}/api/* route matched" apart from
// "/t/{slug}/console, serve the SPA" any other way than checking the shape
// of the path itself.
var tenantAPIPath = regexp.MustCompile(`^/t/[^/]+/api(/|$)`)

// SPAHandler serves the Vue SPA from the embedded dist/ directory.
// It serves static assets directly and falls back to index.html for
// HTML5 history mode routing (any non-file path gets index.html).
func SPAHandler() http.Handler {
	// Strip the "dist" prefix from the embedded filesystem
	distContent, err := fs.Sub(distFS, "dist")
	if err != nil {
		panic("failed to create sub filesystem for dist/: " + err.Error())
	}

	fileServer := http.FileServer(http.FS(distContent))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path

		// Skip API and OAuth proxy routes (handled by other handlers).
		//
		// Stage UI-3a added the tenantAPIPath check: /t/{slug}/api/... does
		// NOT start with /api/, so before this fix a mistyped or
		// unregistered route under it silently served index.html with 200
		// instead of 404 — the SPA's fetch would then receive HTML where it
		// expected JSON. /t/{slug}/admin/... (login/reauth) and
		// /t/{slug}/console are deliberately NOT matched here — those must
		// keep falling through to the SPA/backend redirect handlers below.
		if strings.HasPrefix(path, "/api/") || strings.HasPrefix(path, "/closeauth/") || tenantAPIPath.MatchString(path) {
			http.NotFound(w, r)
			return
		}

		// Try to serve the file directly (CSS, JS, images, etc.)
		if file, err := distContent.Open(strings.TrimPrefix(path, "/")); err == nil {
			file.Close()
			fileServer.ServeHTTP(w, r)
			return
		}

		// Fallback: serve index.html for SPA client-side routing
		r.URL.Path = "/"
		fileServer.ServeHTTP(w, r)
	})
}
