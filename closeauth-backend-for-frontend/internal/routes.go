package server

import (
	"encoding/json"
	"net/http"
	"time"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"
)

func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	// r.Use(chimiddleware.Logger) // Removed: using custom slog logger instead

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

			
	// Health check endpoint (before CSRF to allow monitoring tools)
    r.Get("/health", s.handleHealthCheck)
	
	// OAuth2 proxy endpoints - must be before static files to take precedence
	// These endpoints proxy to Spring Authorization Server
	r.Get("/closeauth/oauth2/authorize", s.oauthProxyHandler.HandleAuthorize)
	r.Post("/closeauth/oauth2/token", s.oauthProxyHandler.HandleToken)
	
	// Serve static files - Go's FileServer handles MIME types automatically
	staticFS := http.Dir("./static")
	staticHandler := http.StripPrefix("/static/", http.FileServer(staticFS))
	r.Handle("/static/*", staticHandler)
	
	// Public routes - can be cached for better performance
	r.Handle("/", templ.Handler(templates.Public()))
	r.Get("/auth/login", s.authHandler.HandleLoginGet)
	r.Get("/auth/register", s.authHandler.HandleRegisterGet)
	r.Get("/auth/forgot-password", s.authHandler.HandleForgotPasswordGet)
	
	// Admin routes - you might want selective no-cache for sensitive pages
	r.Handle("/admin/dashboard", templ.Handler(templates.Dashboard()))
	r.Handle("/admin/users", templ.Handler(templates.Users()))
	r.Get("/admin/clients", s.clientHandler.HandleClients)
	r.Get("/admin/clients/new", s.clientHandler.HandleCreateClientGet)
	r.Post("/admin/clients", s.clientHandler.HandleCreateClientPost)
	
	// Authentication routes
	r.Post("/auth/login", s.authHandler.HandleLoginPost)
	r.Post("/login", s.authHandler.HandleLoginPost)
	r.Post("/register", s.authHandler.HandleRegisterPost)
	r.Post("/register/verify-otp", s.authHandler.HandleVerifyRegistrationOTP)
	r.Post("/register/resend-otp", s.authHandler.HandleResendRegistrationOTP)
	r.Post("/forgot-password/request", s.authHandler.HandleForgotPasswordRequest)
	r.Post("/forgot-password/verify-otp", s.authHandler.HandleVerifyOTP)
	r.Post("/forgot-password/resend-otp", s.authHandler.HandleResendOTP)
	r.Post("/forgot-password/reset", s.authHandler.HandleResetPassword)
	
	    // Catch-all route for 404s - redirect to home page
    r.NotFound(func(w http.ResponseWriter, r *http.Request) {
        http.Redirect(w, r, "/", http.StatusTemporaryRedirect)
    })
	return r
}

// Health check handler
func (s *Server) handleHealthCheck(w http.ResponseWriter, r *http.Request) {
    health := map[string]interface{}{
        "status": "ok",
        "timestamp": time.Now().UTC().Format(time.RFC3339),
    }

    // Check database health
    if err := s.HealthCheck(); err != nil {
        health["status"] = "degraded"
        health["database"] = map[string]string{
            "status": "unhealthy",
            "error":  err.Error(),
        }
        w.WriteHeader(http.StatusServiceUnavailable)
    } else {
        health["database"] = map[string]string{
            "status": "healthy",
        }
        w.WriteHeader(http.StatusOK)
    }

    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(health)
}