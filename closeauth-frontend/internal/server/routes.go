package server

import (
	"closeauth-frontend/internal/spring"
	"encoding/json"
	"fmt"
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
			r.Use(middleware.NoCacheMiddleware)

			// Session management
			r.Get("/admin/me", s.handleAdminMe)
			r.Post("/admin/logout", s.handleAdminLogout)

			// OIDC Dynamic Client Registration (via BFF)
			r.Post("/admin/clients", s.handleAdminCreateClient)

			// Client Configuration — all proxied to Spring with X-User-Token
			r.Route("/admin/clients/{clientId}", func(r chi.Router) {
				// Application Roles
				r.Post("/roles", s.handleCreateRole)
				r.Get("/roles", s.handleGetRoles)
				r.Get("/roles/{roleId}", s.handleGetRole)
				r.Put("/roles/{roleId}", s.handleUpdateRole)
				r.Delete("/roles/{roleId}", s.handleDeleteRole)

				// Registration Config
				r.Get("/registration-config", s.handleGetRegistrationConfig)
				r.Put("/registration-config", s.handleUpdateRegistrationConfig)

				// Themes
				r.Post("/themes", s.handleCreateTheme)
				r.Get("/themes", s.handleGetThemes)
				r.Get("/themes/active", s.handleGetActiveTheme)
				r.Get("/themes/{themeId}", s.handleGetTheme)
				r.Put("/themes/{themeId}", s.handleUpdateTheme)
				r.Delete("/themes/{themeId}", s.handleDeleteTheme)
				r.Patch("/themes/{themeId}/activate", s.handleActivateTheme)

				// Theme Configurations
				r.Post("/themes/{themeId}/configurations", s.handleCreateThemeConfig)
				r.Get("/themes/{themeId}/configurations", s.handleGetThemeConfigs)
				r.Get("/themes/{themeId}/configurations/{configId}", s.handleGetThemeConfig)
				r.Put("/themes/{themeId}/configurations/{configId}", s.handleUpdateThemeConfig)
				r.Delete("/themes/{themeId}/configurations/{configId}", s.handleDeleteThemeConfig)

				// Admin Approval (Pending Registrations)
				r.Get("/pending-registrations", s.handleGetPendingRegistrations)
				r.Get("/pending-registrations/count", s.handleGetPendingRegistrationsCount)
				r.Post("/pending-registrations/{email}/approve", s.handleApproveRegistration)
				r.Post("/pending-registrations/{email}/reject", s.handleRejectRegistration)
			})
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

// --- Protected Admin (data endpoints) ---

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
	// CLear all BFF cookies to fully terminate the session.
	// The user JWT is stateless and cannot be revoked, so clearing the cookie is the only way to log out.
	// without registering them in Spring's OauthAuthorizationService, so
	// server-side revocation via /oauth2/revoke is not possible. Cookie cleanup
	// is the primary logout mechainsm;
	middleware.ClearSession(w)
	middleware.ClearOAuthContext(w)
	middleware.ClearCSRFToken(w)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]bool{"success": true})
}

func (s *Server) handleAdminCreateClient(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "admin_create_client")

	var formReq spring.ClientFormRequest
	if err := json.NewDecoder(r.Body).Decode(&formReq); err != nil {
		jsonError(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if formReq.ClientName == "" {
		jsonError(w, "Client name is required", http.StatusBadRequest)
		return
	}
	if len(formReq.RedirectURIs) == 0 {
		jsonError(w, "At least one redirect_uri is required", http.StatusBadRequest)
		return
	}

	token, err := s.springClient.GetAccessToken(r.Context())
	if err != nil {
		logger.Error("failed to get access token for client registration", "error", err)
		jsonError(w, "Authorization service unavailable", http.StatusServiceUnavailable)
		return
	}

	regResp, err := s.springClient.RegisterClient(r.Context(), token, &formReq)
	if err != nil {
		logger.Error("client registration failed", "error", err)
		jsonError(w, fmt.Sprintf("Client registration failed: %s", err.Error()), http.StatusInternalServerError)
		return
	}

	logger.Info("client registered successfully", "client_id", regResp.ClientID, "client_name", regResp.ClientName)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(regResp)
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
