package handlers

import (
	"log"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// ClientHandler contains dependencies for client management handlers
type ClientHandler struct {
	// Add dependencies here if needed (e.g., database, client service)
}

// NewClientHandler creates a new client handler instance
func NewClientHandler() *ClientHandler {
	return &ClientHandler{}
}

// HandleClients displays the clients list page
func (h *ClientHandler) HandleClients(w http.ResponseWriter, r *http.Request) {
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
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleCreateClientGet displays the create client form
func (h *ClientHandler) HandleCreateClientGet(w http.ResponseWriter, r *http.Request) {
	// Create sample data for the create client form
	data := templates.CreateClientData{
		AvailableScopes: []templates.Scope{
			{Value: "openid", Name: "OpenID Connect", Description: "Basic identity information"},
			{Value: "profile", Name: "Profile", Description: "User profile information"},
			{Value: "email", Name: "Email", Description: "User email address"},
			{Value: "offline_access", Name: "Offline Access", Description: "Refresh tokens"},
			{Value: "read:users", Name: "Read Users", Description: "Read user data"},
			{Value: "write:users", Name: "Write Users", Description: "Modify user data"},
		},
		ApplicationTypes: []templates.ApplicationType{
			{Value: "spa", Label: "Single Page Application (SPA)"},
			{Value: "web", Label: "Web Application"},
			{Value: "native", Label: "Native/Mobile Application"},
			{Value: "m2m", Label: "Machine to Machine"},
		},
	}

	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Render the template
	component := templates.CreateClient(data, csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleCreateClientPost processes the create client form submission
func (h *ClientHandler) HandleCreateClientPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		http.Error(w, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	name := r.FormValue("name")
	description := r.FormValue("description")
	clientType := r.FormValue("type")
	logoUrl := r.FormValue("logoUrl")
	redirectUris := r.Form["redirectUris[]"]
	scopes := r.Form["scopes[]"]

	// Basic validation
	if name == "" || clientType == "" {
		http.Error(w, "Name and type are required", http.StatusBadRequest)
		return
	}

	// In a real application, you would save this to a database
	log.Printf("Creating new client: name=%s, type=%s, description=%s, logoUrl=%s, redirectUris=%v, scopes=%v", 
		name, clientType, description, logoUrl, redirectUris, scopes)

	// Redirect back to clients list
	http.Redirect(w, r, "/admin/clients", http.StatusSeeOther)
}