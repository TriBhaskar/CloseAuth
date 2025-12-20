package server

import (
	"closeauth-backend-for-frontend/internal/constants"
	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"
	"encoding/json"
	"net/http"
	"time"

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
    r.Get(constants.RouteHealth, s.handleHealthCheck)
	
	// OAuth2 proxy endpoints - must be before static files to take precedence
	// These endpoints proxy to Spring Authorization Server
	r.Get(constants.RouteOAuthAuthorize, s.oauthProxyHandler.HandleAuthorize)
	r.Post(constants.RouteOAuthToken, s.oauthProxyHandler.HandleToken)
	
	// Serve static files - Go's FileServer handles MIME types automatically
	staticFS := http.Dir("./static")
	staticHandler := http.StripPrefix(constants.RouteStatic, http.FileServer(staticFS))
	r.Handle("/static/*", staticHandler)
	
	// Public routes - can be cached for better performance
	r.Handle(constants.RouteHome, templ.Handler(templates.Public()))
	r.Get(constants.RouteAdminLogin, s.authHandler.HandleLoginGet)
	r.Get(constants.RouteAdminRegister, s.authHandler.HandleRegisterGet)
	r.Get(constants.RouteAdminForgotPassword, s.authHandler.HandleForgotPasswordGet)
	
	// Admin routes - you might want selective no-cache for sensitive pages
	r.Handle(constants.RouteAdminDashboard, templ.Handler(templates.Dashboard()))
	r.Handle(constants.RouteAdminUsers, templ.Handler(templates.Users()))
	r.Get(constants.RouteAdminClients, s.clientHandler.HandleClients)
	r.Get(constants.RouteAdminClientNew, s.clientHandler.HandleCreateClientGet)
	r.Post(constants.RouteAdminClients, s.clientHandler.HandleCreateClientPost)
	
	// Authentication routes
	r.Post(constants.RouteLogin, s.authHandler.HandleLoginPost)
	r.Post(constants.RouteRegister, s.authHandler.HandleRegisterPost)
	r.Post(constants.RouteRegisterVerify, s.authHandler.HandleVerifyRegistrationOTP)
	r.Post(constants.RouteRegisterResend, s.authHandler.HandleResendRegistrationOTP)
	r.Post(constants.RouteForgotPasswordRequest, s.authHandler.HandleForgotPasswordRequest)
	r.Post(constants.RouteForgotPasswordVerify, s.authHandler.HandleVerifyOTP)
	r.Post(constants.RouteForgotPasswordResend, s.authHandler.HandleResendOTP)
	r.Post(constants.RouteForgotPasswordReset, s.authHandler.HandleResetPassword)
	
	// OAuth2 client-specific themed authentication routes
	// These pages display client-branded login/register based on client_id parameter
	r.Get(constants.RouteOAuthClientLogin, s.oauthClientAuthHandler.HandleOAuthLoginGet)
	r.Post(constants.RouteOAuthClientLoginPost, s.oauthClientAuthHandler.HandleOAuthLoginPost)
	r.Get(constants.RouteOAuthClientRegister, s.oauthClientAuthHandler.HandleOAuthRegisterGet)
	r.Post(constants.RouteOAuthClientRegisterPost, s.oauthClientAuthHandler.HandleOAuthRegisterPost)
	r.Post(constants.RouteOAuthClientRegisterVerifyOTP, s.oauthClientAuthHandler.HandleOAuthVerifyRegistrationOTP)
	r.Post(constants.RouteOAuthClientRegisterResendOTP, s.oauthClientAuthHandler.HandleOAuthResendRegistrationOTP)
	
	    // Catch-all route for 404s - redirect to home page
    r.NotFound(func(w http.ResponseWriter, r *http.Request) {
        http.Redirect(w, r, constants.RouteHome, http.StatusTemporaryRedirect)
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