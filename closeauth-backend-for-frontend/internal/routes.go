package server

import (
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	r.Use(chimiddleware.Logger)

	// CSRF protection
	csrfConfig := middleware.DefaultCSRFConfig()
	r.Use(middleware.CSRFTokenMiddleware(csrfConfig))
	r.Use(middleware.CSRFMiddleware(csrfConfig))

	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// Serve static files - Go's FileServer handles MIME types automatically
	staticFS := http.Dir("./static")
	staticHandler := http.StripPrefix("/static/", http.FileServer(staticFS))
	r.Handle("/static/*", staticHandler)
	
	// Public routes - can be cached for better performance
	r.Handle("/", templ.Handler(templates.Public()))
	r.Get("/auth/login", s.authHandler.HandleLoginGet)
	r.Get("/auth/register", s.authHandler.HandleRegisterGet)
	
	// Admin routes - you might want selective no-cache for sensitive pages
	r.Handle("/admin/dashboard", templ.Handler(templates.Dashboard()))
	r.Handle("/admin/users", templ.Handler(templates.Users()))
	r.Get("/admin/clients", s.clientHandler.HandleClients)
	r.Get("/admin/clients/new", s.clientHandler.HandleCreateClientGet)
	r.Post("/admin/clients", s.clientHandler.HandleCreateClientPost)
	
	// Authentication routes
	r.Post("/login", s.authHandler.HandleLoginPost)
	r.Post("/register", s.authHandler.HandleRegisterPost)
	
	    // Catch-all route for 404s - redirect to home page
    r.NotFound(func(w http.ResponseWriter, r *http.Request) {
        http.Redirect(w, r, "/", http.StatusTemporaryRedirect)
    })
	return r
}
