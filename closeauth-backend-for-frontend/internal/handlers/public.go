package handlers

import (
	"encoding/json"
	"log"
	"net/http"
	"path/filepath"
	"strings"
)

// PublicHandler contains dependencies for public/general handlers
type PublicHandler struct {
	// Add dependencies here if needed
}

// NewPublicHandler creates a new public handler instance
func NewPublicHandler() *PublicHandler {
	return &PublicHandler{}
}

// HelloWorldHandler returns a simple JSON hello world response
func (h *PublicHandler) HelloWorldHandler(w http.ResponseWriter, r *http.Request) {
	resp := map[string]string{"message": "Hello World"}
	jsonResp, err := json.Marshal(resp)
	if err != nil {
		http.Error(w, "Failed to marshal response", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if _, err := w.Write(jsonResp); err != nil {
		log.Printf("Failed to write response: %v", err)
	}
}

// NoCacheMiddleware adds headers to prevent caching during development
func (h *PublicHandler) NoCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Add no-cache headers for development
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
		
		// Set correct MIME types for static files
		ext := filepath.Ext(r.URL.Path)
		switch strings.ToLower(ext) {
		case ".css":
			w.Header().Set("Content-Type", "text/css; charset=utf-8")
		case ".js":
			w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		case ".svg":
			w.Header().Set("Content-Type", "image/svg+xml")
		case ".png":
			w.Header().Set("Content-Type", "image/png")
		case ".jpg", ".jpeg":
			w.Header().Set("Content-Type", "image/jpeg")
		case ".gif":
			w.Header().Set("Content-Type", "image/gif")
		case ".ico":
			w.Header().Set("Content-Type", "image/x-icon")
		}
		
		next.ServeHTTP(w, r)
	})
}