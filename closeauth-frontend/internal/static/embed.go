package static

import (
	"embed"
	"io/fs"
	"net/http"
	"strings"
)

//go:embed all:dist
var distFS embed.FS

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

		// Skip API and OAuth proxy routes (handled by other handlers)
		if strings.HasPrefix(path, "/api/") || strings.HasPrefix(path, "/closeauth/") {
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
