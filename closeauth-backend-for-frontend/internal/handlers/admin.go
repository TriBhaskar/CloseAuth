package handlers

import (
	"log/slog"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	"closeauth-backend-for-frontend/internal/templates/components/auth"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// AdminHandler contains dependencies for admin page handlers
type AdminHandler struct {
	logger *slog.Logger
}

// NewAdminHandler creates a new admin handler instance
func NewAdminHandler() *AdminHandler {
	return &AdminHandler{
		logger: slog.Default().With("handler", "admin"),
	}
}

// HandleDashboard renders the dashboard page with user session
func (h *AdminHandler) HandleDashboard(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		h.logger.Error("failed to get session", "error", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Dashboard(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleUsers renders the users page with user session
func (h *AdminHandler) HandleUsers(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		h.logger.Error("failed to get session", "error", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Users(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleAnalytics renders the analytics page with user session
func (h *AdminHandler) HandleAnalytics(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		h.logger.Error("failed to get session", "error", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Analytics(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleSecurity renders the security page with user session
func (h *AdminHandler) HandleSecurity(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		h.logger.Error("failed to get session", "error", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Security(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleSettings renders the settings page with user session
func (h *AdminHandler) HandleSettings(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		h.logger.Error("failed to get session", "error", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Settings(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleRegisterOTP shows the OTP verification page to the user
func (h *AdminHandler) HandleRegisterOTP(w http.ResponseWriter, r *http.Request) {
	email := r.URL.Query().Get("email")
	if email == "" {
		http.Error(w, "Email is required", http.StatusBadRequest)
		return
	}

	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	otpDialog := auth.AdminOTPDialog(csrfToken, email)
	templ.Handler(otpDialog).ServeHTTP(w, r)
}

// HandleVerifyOTP handles the form submission from the OTP verification page
func (h *AdminHandler) HandleVerifyOTP(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	email := r.FormValue("email")
	otp := r.FormValue("otp")

	// Basic validation
	if email == "" || otp == "" {
		http.Error(w, "Email and OTP are required", http.StatusBadRequest)
		return
	}

	// TODO: Add actual OTP verification logic here.
	// For now, we'll assume it's correct and log it.
	h.logger.Info("Admin OTP verification successful", "email", email)

	// Redirect to login page with a success message
	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, "/admin/login?verified=true")
	} else {
		http.Redirect(w, r, "/admin/login?verified=true", http.StatusSeeOther)
	}
}
