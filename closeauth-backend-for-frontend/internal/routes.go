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

	// Serve static files with proper cache headers
	staticFS := http.Dir("./static")
	staticHandler := http.StripPrefix("/static/", http.FileServer(staticFS))
	r.Handle("/static/*", s.publicHandler.NoCacheMiddleware(staticHandler))
	
	// Main page with no-cache headers
	r.Handle("/", s.publicHandler.NoCacheMiddleware(templ.Handler(templates.Public())))
	r.Handle("/auth/login", s.publicHandler.NoCacheMiddleware(http.HandlerFunc(s.authHandler.HandleLoginGet)))
	r.Handle("/auth/register", s.publicHandler.NoCacheMiddleware(templ.Handler(templates.Register())))
	r.Handle("/admin/dashboard", s.publicHandler.NoCacheMiddleware(templ.Handler(templates.Dashboard())))
	r.Handle("/admin/users", s.publicHandler.NoCacheMiddleware(templ.Handler(templates.Users())))
	r.Handle("/admin/clients", s.publicHandler.NoCacheMiddleware(http.HandlerFunc(s.clientHandler.HandleClients)))
	r.Handle("/admin/clients/new", s.publicHandler.NoCacheMiddleware(http.HandlerFunc(s.clientHandler.HandleCreateClientGet)))
	r.Post("/admin/clients", s.clientHandler.HandleCreateClientPost)
	
	// Authentication routes
	r.Post("/login", s.authHandler.HandleLoginPost)
	
	    // Catch-all route for 404s - redirect to home page
    r.NotFound(func(w http.ResponseWriter, r *http.Request) {
        http.Redirect(w, r, "/", http.StatusTemporaryRedirect)
    })
	return r
}
