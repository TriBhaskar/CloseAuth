package handlers

import (
	"log"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// AuthHandler contains dependencies for authentication handlers
type AuthHandler struct {
	// Add dependencies here if needed (e.g., database, auth service)
}

// NewAuthHandler creates a new auth handler instance
func NewAuthHandler() *AuthHandler {
	return &AuthHandler{}
}

// HandleLoginGet renders the login form
func (h *AuthHandler) HandleLoginGet(w http.ResponseWriter, r *http.Request) {
	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render login template with CSRF token
	component := templates.Login(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleLoginPost processes login form submission
func (h *AuthHandler) HandleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleLoginError(w, r, "Failed to parse form data", http.StatusBadRequest)
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
		h.handleLoginError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
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
		h.handleLoginError(w, r, "Invalid email or password. Try: test@test.com / password", http.StatusUnauthorized)
	}
}

// handleLoginError handles login errors for both HTMX and regular requests
// This is a more efficient approach that only sends the error message
func (h *AuthHandler) handleLoginError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
	if middleware.IsHTMXRequest(r) {
		// For HTMX requests, return only the error message HTML
		// This should target an error container in the form
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		
		errorHTML := `<div id="error-message" class="mb-4 p-3 rounded-md bg-red-50 border border-red-200">
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
		</div>`
		w.Write([]byte(errorHTML))
	} else {
		// For regular requests, return standard error
		http.Error(w, message, statusCode)
	}
}