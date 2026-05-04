package server

import (
	"encoding/json"
	"net/http"
	"time"

	"closeauth-frontend/internal/middleware"
	"closeauth-frontend/internal/static"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	r.Use(chimw.Logger)
	r.Use(chimw.Recoverer)

	// CORS — allow Vue dev server and same-origin in production
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// CSRF token generation (on every request)
	isProduction := s.springConfig.IsProduction()
	r.Use(middleware.CSRFTokenMiddleware(isProduction))

	// ──────────────────────────────────────────────────────────────────────────
	// Tier 1: Browser-navigation OAuth routes (native http.Redirect)
	// These are hit by browser navigation, NOT SPA fetch.
	// ──────────────────────────────────────────────────────────────────────────
	r.Route("/closeauth", func(r chi.Router) {
		r.Get("/oauth2/authorize", s.handleAuthorize)
		r.Post("/oauth2/token", s.handleToken)

		// Consent POST is a native HTML form submission — CSRF via form field
		r.With(middleware.CSRFValidationMiddleware).Post("/oauth2/consent", s.handleConsentPost)
	})

	// ──────────────────────────────────────────────────────────────────────────
	// Tier 2: JSON API routes for SPA fetch
	// ──────────────────────────────────────────────────────────────────────────
	r.Route("/api", func(r chi.Router) {
		// CSRF validation on all mutating API requests
		r.Use(middleware.CSRFValidationMiddleware)

		// Public API endpoints (no auth required)
		r.Get("/csrf", middleware.HandleCSRFToken)
		r.Get("/health", s.handleHealthCheck)

		// Admin auth (public — login/register/forgot-password)
		r.Post("/admin/login", s.handleAdminLogin)
		r.Post("/admin/register", s.handleAdminRegister)
		r.Post("/admin/register/verify-otp", s.handleAdminVerifyOTP)
		r.Post("/admin/register/resend-otp", s.handleAdminResendOTP)
		r.Post("/admin/forgot-password/request", s.handleForgotPasswordRequest)
		r.Post("/admin/forgot-password/verify-otp", s.handleForgotPasswordVerifyOTP)
		r.Post("/admin/forgot-password/resend", s.handleForgotPasswordResend)
		r.Post("/admin/forgot-password/reset", s.handleForgotPasswordReset)

		// OAuth client pages (public — theme, login, register, consent-data)
		r.Get("/oauth/theme", s.handleOAuthTheme)
		r.Post("/oauth/login", s.handleOAuthLogin)
		r.Post("/oauth/register", s.handleOAuthRegister)
		r.Post("/oauth/register/verify-otp", s.handleOAuthVerifyOTP)
		r.Post("/oauth/register/resend-otp", s.handleOAuthResendOTP)
		r.Get("/oauth/consent-data", s.handleOAuthConsentData)

		// Protected admin routes (require session)
		r.Group(func(r chi.Router) {
			r.Use(middleware.RequireAuth)

			r.Get("/admin/me", s.handleAdminMe)
			r.Post("/admin/logout", s.handleAdminLogout)
			r.Get("/admin/dashboard", s.handleAdminDashboard)
			r.Get("/admin/users", s.handleAdminUsers)
			r.Get("/admin/clients", s.handleAdminClients)
			r.Post("/admin/clients", s.handleAdminCreateClient)
			r.Get("/admin/analytics", s.handleAdminAnalytics)
			r.Get("/admin/security", s.handleAdminSecurity)
			r.Put("/admin/settings", s.handleAdminSettings)
		})
	})

	// ──────────────────────────────────────────────────────────────────────────
	// Tier 3: SPA catch-all — serve embedded Vue dist/ (fallback to index.html)
	// ──────────────────────────────────────────────────────────────────────────
	r.NotFound(static.SPAHandler().ServeHTTP)

	return r
}

// ──────────────────────────────────────────────────────────────────────────────
// Handler wiring — implementations live in handlers_*.go files
// ──────────────────────────────────────────────────────────────────────────────

// --- OAuth Proxy (browser-navigation) → handlers_oauth_proxy.go ---

func (s *Server) handleAuthorize(w http.ResponseWriter, r *http.Request) {
	s.handleAuthorizeImpl(w, r)
}

func (s *Server) handleToken(w http.ResponseWriter, r *http.Request) {
	s.handleTokenImpl(w, r)
}

func (s *Server) handleConsentPost(w http.ResponseWriter, r *http.Request) {
	s.handleConsentPostImpl(w, r)
}

// --- Admin Auth → handlers_admin_auth.go ---

func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	s.handleAdminLoginImpl(w, r)
}

func (s *Server) handleAdminRegister(w http.ResponseWriter, r *http.Request) {
	s.handleAdminRegisterImpl(w, r)
}

func (s *Server) handleAdminVerifyOTP(w http.ResponseWriter, r *http.Request) {
	s.handleAdminVerifyOTPImpl(w, r)
}

func (s *Server) handleAdminResendOTP(w http.ResponseWriter, r *http.Request) {
	s.handleAdminResendOTPImpl(w, r)
}

func (s *Server) handleForgotPasswordRequest(w http.ResponseWriter, r *http.Request) {
	s.handleForgotPasswordRequestImpl(w, r)
}

func (s *Server) handleForgotPasswordVerifyOTP(w http.ResponseWriter, r *http.Request) {
	s.handleForgotPasswordVerifyOTPImpl(w, r)
}

func (s *Server) handleForgotPasswordResend(w http.ResponseWriter, r *http.Request) {
	s.handleForgotPasswordResendImpl(w, r)
}

func (s *Server) handleForgotPasswordReset(w http.ResponseWriter, r *http.Request) {
	s.handleForgotPasswordResetImpl(w, r)
}

// --- OAuth Client Pages → handlers_oauth_client.go ---

func (s *Server) handleOAuthTheme(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthThemeImpl(w, r)
}

func (s *Server) handleOAuthLogin(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthLoginImpl(w, r)
}

func (s *Server) handleOAuthRegister(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthRegisterImpl(w, r)
}

func (s *Server) handleOAuthVerifyOTP(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthVerifyOTPImpl(w, r)
}

func (s *Server) handleOAuthResendOTP(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthResendOTPImpl(w, r)
}

func (s *Server) handleOAuthConsentData(w http.ResponseWriter, r *http.Request) {
	s.handleOAuthConsentDataImpl(w, r)
}

// --- Protected Admin (data endpoints — TODO: proxy to Spring admin APIs) ---

func (s *Server) handleAdminMe(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetSession(r)
	if err != nil {
		jsonError(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"email":    session.Email,
		"username": session.Username,
		"role":     session.Role,
	})
}

func (s *Server) handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	middleware.ClearSession(w)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]bool{"success": true})
}

func (s *Server) handleAdminDashboard(w http.ResponseWriter, r *http.Request) {
	// TODO: Proxy to Spring admin dashboard API when available
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Dashboard data endpoint pending Spring API"})
}

func (s *Server) handleAdminUsers(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Users endpoint pending Spring API"})
}

func (s *Server) handleAdminClients(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Clients endpoint pending Spring API"})
}

func (s *Server) handleAdminCreateClient(w http.ResponseWriter, r *http.Request) {
	// TODO: Use s.springClient.GetAccessToken + RegisterClient
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Client creation pending"})
}

func (s *Server) handleAdminAnalytics(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Analytics endpoint pending Spring API"})
}

func (s *Server) handleAdminSecurity(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Security endpoint pending Spring API"})
}

func (s *Server) handleAdminSettings(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "not_implemented", "message": "Settings endpoint pending"})
}

// --- Health Check ---

func (s *Server) handleHealthCheck(w http.ResponseWriter, r *http.Request) {
	health := map[string]interface{}{
		"status":    "ok",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	}

	if err := s.HealthCheck(); err != nil {
		health["status"] = "degraded"
		health["database"] = map[string]string{"status": "unhealthy", "error": err.Error()}
		w.WriteHeader(http.StatusServiceUnavailable)
	} else {
		health["database"] = map[string]string{"status": "healthy"}
		w.WriteHeader(http.StatusOK)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(health)
}
