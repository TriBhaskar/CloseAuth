package handlers

import (
	"log"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// AdminHandler contains dependencies for admin page handlers
type AdminHandler struct {
	// Add dependencies here if needed
}

// NewAdminHandler creates a new admin handler instance
func NewAdminHandler() *AdminHandler {
	return &AdminHandler{}
}

// HandleDashboard renders the dashboard page with user session
func (h *AdminHandler) HandleDashboard(w http.ResponseWriter, r *http.Request) {
	session, err := middleware.GetValidSession(r)
	if err != nil {
		log.Printf("Error getting session: %v", err)
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
		log.Printf("Error getting session: %v", err)
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
		log.Printf("Error getting session: %v", err)
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
		log.Printf("Error getting session: %v", err)
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
		log.Printf("Error getting session: %v", err)
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
		return
	}

	component := templates.Settings(session.Email)
	templ.Handler(component).ServeHTTP(w, r)
}
