package server

import (
	"encoding/json"
	"log"
	"net/http"

	templates "closeauth-backend-for-frontend/internal/templates/layouts"

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
	
	// Main page with no-cache headers
	r.Handle("/", s.noCacheMiddleware(templ.Handler(templates.Public())))
	r.Handle("/auth/login", s.noCacheMiddleware(templ.Handler(templates.Login())))
	r.Handle("/auth/register", s.noCacheMiddleware(templ.Handler(templates.Register())))
	r.Handle("/admin/dashboard", s.noCacheMiddleware(templ.Handler(templates.Dashboard())))
	r.Handle("/admin/users", s.noCacheMiddleware(templ.Handler(templates.Users())))
	r.Handle("/admin/clients", s.noCacheMiddleware(http.HandlerFunc(s.handleClients)))
	    // Catch-all route for 404s - redirect to home page
    r.NotFound(func(w http.ResponseWriter, r *http.Request) {
        http.Redirect(w, r, "/", http.StatusTemporaryRedirect)
    })
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

func (s *Server) handleClients(w http.ResponseWriter, r *http.Request) {
	// Sample data - in a real application, this would come from a database
	stats := templates.ClientStats{
		TotalClients:  4,
		ActiveClients: 3,
		WebApps:       2,
		MobileApps:    1,
	}

	clients := []templates.Client{
		{
			ID:        "web_dash_123",
			Name:      "Web Dashboard",
			Type:      "SPA",
			Status:    "active",
			CreatedAt: "Jan 15, 2024",
			LastUsed:  "5 days ago",
			Scopes:    3,
			RedirectURIs: []string{"https://example.com/callback"},
		},
		{
			ID:        "mobile_app_456",
			Name:      "Mobile App",
			Type:      "Native",
			Status:    "active",
			CreatedAt: "Jan 10, 2024",
			LastUsed:  "6 days ago",
			Scopes:    2,
			RedirectURIs: []string{"com.example.app://callback"},
		},
		{
			ID:        "api_service_789",
			Name:      "API Service",
			Type:      "M2M",
			Status:    "inactive",
			CreatedAt: "Jan 8, 2024",
			LastUsed:  "17 days ago",
			Scopes:    2,
			RedirectURIs: []string{},
		},
		{
			ID:        "analytics_abc",
			Name:      "Analytics Dashboard",
			Type:      "SPA",
			Status:    "active",
			CreatedAt: "Jan 20, 2024",
			LastUsed:  "5 days ago",
			Scopes:    3,
			RedirectURIs: []string{"https://analytics.example.com/callback"},
		},
	}

	// Render the template
	component := templates.Clients(stats, clients)
	s.noCacheMiddleware(templ.Handler(component)).ServeHTTP(w, r)
}
