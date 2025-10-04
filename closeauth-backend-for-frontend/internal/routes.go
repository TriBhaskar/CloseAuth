package server

import (
	"encoding/json"
	"log"
	"net/http"

	"closeauth-backend-for-frontend/internal/templates"

	"github.com/a-h/templ"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Logger)

	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// Serve static files with proper cache headers
	staticFS := http.Dir("./static")
	staticHandler := http.StripPrefix("/static/", http.FileServer(staticFS))
	r.Handle("/static/*", s.noCacheMiddleware(staticHandler))
	
	// Serve template assets if they exist
	fileServer := http.FileServer(http.FS(templates.Files))
	r.Handle("/assets/*", fileServer)
	
	// Main page with no-cache headers
	r.Handle("/", s.noCacheMiddleware(templ.Handler(templates.Public())))

	return r
}

// noCacheMiddleware adds headers to prevent caching during development
func (s *Server) noCacheMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Add no-cache headers for development
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
		next.ServeHTTP(w, r)
	})
}

// func (s *Server) corsMiddleware(next http.Handler) http.Handler {
// 	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
// 		// Set CORS headers
// 		w.Header().Set("Access-Control-Allow-Origin", "*") // Replace "*" with specific origins if needed
// 		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
// 		w.Header().Set("Access-Control-Allow-Headers", "Accept, Authorization, Content-Type, X-CSRF-Token")
// 		w.Header().Set("Access-Control-Allow-Credentials", "false") // Set to "true" if credentials are required

// 		// Handle preflight OPTIONS requests
// 		if r.Method == http.MethodOptions {
// 			w.WriteHeader(http.StatusNoContent)
// 			return
// 		}

// 		// Proceed with the next handler
// 		next.ServeHTTP(w, r)
// 	})
// }

func (s *Server) HelloWorldHandler(w http.ResponseWriter, r *http.Request) {
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
