package server

import (
	"encoding/json"
	"log"
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
	r.Handle("/static/*", s.noCacheMiddleware(staticHandler))
	
	// Main page with no-cache headers
	r.Handle("/", s.noCacheMiddleware(templ.Handler(templates.Public())))
	r.Handle("/auth/login", s.noCacheMiddleware(http.HandlerFunc(s.handleLoginGet)))
	r.Handle("/auth/register", s.noCacheMiddleware(templ.Handler(templates.Register())))
	r.Handle("/admin/dashboard", s.noCacheMiddleware(templ.Handler(templates.Dashboard())))
	r.Handle("/admin/users", s.noCacheMiddleware(templ.Handler(templates.Users())))
	r.Handle("/admin/clients", s.noCacheMiddleware(http.HandlerFunc(s.handleClients)))
	r.Handle("/admin/clients/new", s.noCacheMiddleware(http.HandlerFunc(s.handleCreateClientGet)))
	r.Post("/admin/clients", s.handleCreateClientPost)
	
	// Authentication routes
	r.Post("/login", s.handleLoginPost)
	
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

func (s *Server) handleCreateClientGet(w http.ResponseWriter, r *http.Request) {
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

func (s *Server) handleCreateClientPost(w http.ResponseWriter, r *http.Request) {
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

func (s *Server) handleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		s.handleLoginError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	email := r.FormValue("email")
	password := r.FormValue("password")
	rememberMe := r.FormValue("remember-me") == "on"

	// Validate form data
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Email("email", email, "Please enter a valid email address")
	validator.Required("password", password, "Password is required")
	validator.MinLength("password", password, 6, "Password must be at least 6 characters")

	if !validator.IsValid() {
		s.handleLoginError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// In a real application, you would:
	// 1. Validate credentials against database
	// 2. Create a session or JWT token
	// 3. Set authentication cookies
	
	log.Printf("Login attempt: email=%s, password=%s, rememberMe=%t", email, password, rememberMe)
	log.Printf("HTMX Request: %t", middleware.IsHTMXRequest(r))
	
	// Simulate authentication logic (accept multiple test credentials)
	validCredentials := map[string]string{
		"admin@example.com": "password123",
		"test@test.com":     "password",
		"user@demo.com":     "demo123",
	}
	
	if validPassword, exists := validCredentials[email]; exists && password == validPassword {
		// Success - handle based on request type
		if middleware.IsHTMXRequest(r) {
			// For HTMX requests, redirect using HX-Redirect header
			middleware.HTMXRedirect(w, "/admin/dashboard")
		} else {
			// For regular requests, use standard redirect
			http.Redirect(w, r, "/admin/dashboard", http.StatusSeeOther)
		}
	} else {
		// Invalid credentials
		log.Printf("Invalid credentials - Valid test accounts: admin@example.com/password123, test@test.com/password, user@demo.com/demo123")
		s.handleLoginError(w, r, "Invalid email or password. Try: test@test.com / password", http.StatusUnauthorized)
	}
}

func (s *Server) handleLoginError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
	if middleware.IsHTMXRequest(r) {
		// For HTMX requests, return the form with error message as 200 OK
		// so HTMX will replace the content naturally
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK) // Changed from statusCode to 200
		
		// Get CSRF token from context
		csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
		
		// Get form values to preserve them
		email := r.FormValue("email")
		
		errorHTML := `
		<form class="space-y-6" action="/login" method="POST" 
			  hx-post="/login"
			  hx-target="#form-container" 
			  hx-indicator="#loading"
			  hx-swap="innerHTML"
			  hx-on::before-request="clearErrors()"
			  hx-on::response-error="handleLoginError(event)"
			  hx-disabled-elt="button[type=submit]"
			  id="login-form">
			<input type="hidden" name="csrf_token" value="` + csrfToken + `"/>
			
			<!-- Error message -->
			<div id="error-message" class="p-3 rounded-md bg-red-50 border border-red-200">
				<div class="flex">
					<div class="flex-shrink-0">
						<svg class="h-5 w-5 text-red-400" fill="currentColor" viewBox="0 0 20 20">
							<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"></path>
						</svg>
					</div>
					<div class="ml-3">
						<p class="text-sm text-red-800">` + message + `</p>
					</div>
				</div>
			</div>
			
			<div>
				<label for="email" class="block text-sm font-medium text-gray-700">
					Email address
				</label>
				<input
					id="email"
					name="email"
					type="email"
					autocomplete="email"
					required
					value="` + email + `"
					class="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-indigo-500 sm:text-sm"
					placeholder="Enter your email"
				/>
			</div>
			<div>
				<label for="password" class="block text-sm font-medium text-gray-700">
					Password
				</label>
				<input
					id="password"
					name="password"
					type="password"
					autocomplete="current-password"
					required
					class="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-indigo-500 sm:text-sm"
					placeholder="Enter your password"
				/>
			</div>
			<div class="flex items-center justify-between">
				<div class="flex items-center">
					<input
						id="remember-me"
						name="remember-me"
						type="checkbox"
						class="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
					/>
					<label for="remember-me" class="ml-2 block text-sm text-gray-900">
						Remember me
					</label>
				</div>
				<div class="text-sm">
					<a href="/forgot-password" class="font-medium text-indigo-600 hover:text-indigo-500">
						Forgot your password?
					</a>
				</div>
			</div>
			<div>
				<button
					type="submit"
					class="group relative flex w-full justify-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 disabled:opacity-50"
				>
					<span class="htmx-indicator" id="loading">
						<svg class="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
							<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
							<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
						</svg>
					</span>
					Sign in
				</button>
			</div>
		</form>`
		w.Write([]byte(errorHTML))
	} else {
		// For regular requests, return standard error
		http.Error(w, message, statusCode)
	}
}

func (s *Server) handleLoginGet(w http.ResponseWriter, r *http.Request) {
	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render login template with CSRF token
	component := templates.Login(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}
