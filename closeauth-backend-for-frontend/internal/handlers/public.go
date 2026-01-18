package handlers

import (
	"encoding/json"
	"log"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// PublicHandler contains dependencies for public/general handlers
type PublicHandler struct {
	// Add dependencies here if needed
}

// NewPublicHandler creates a new public handler instance
func NewPublicHandler() *PublicHandler {
	return &PublicHandler{}
}

// HandleHome renders the home page with conditional UI based on login state
func (h *PublicHandler) HandleHome(w http.ResponseWriter, r *http.Request) {
	// Set no-cache headers to prevent stale authenticated state after logout
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Expires", "0")

	// Check if user is logged in
	session, err := middleware.GetValidSession(r)
	isLoggedIn := err == nil && session != nil

	var userEmail string
	if isLoggedIn {
		userEmail = session.Email
	}

	// Render public template with login state
	component := templates.PublicWithAuth(isLoggedIn, userEmail)
	templ.Handler(component).ServeHTTP(w, r)
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
// NOTE: Currently unused - removed from routes for better performance
// Uncomment and use selectively for sensitive pages if needed
/*
func (h *PublicHandler) NoCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Add no-cache headers for development
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
		
		next.ServeHTTP(w, r)
	})
}
*/