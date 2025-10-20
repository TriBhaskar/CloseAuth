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

// HandleRegisterGet renders the register form
func (h *AuthHandler) HandleRegisterGet(w http.ResponseWriter, r *http.Request) {
	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render register template with CSRF token
	component := templates.Register(csrfToken)
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

// HandleRegisterPost processes register form submission
func (h *AuthHandler) HandleRegisterPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	firstName := r.FormValue("firstName")
	lastName := r.FormValue("lastName")
	email := r.FormValue("email")
	username := r.FormValue("username")
	password := r.FormValue("password")
	confirmPassword := r.FormValue("confirmPassword")
	termsAccepted := r.FormValue("terms") == "on"

	// Validate form data
	validator := middleware.NewFormValidator()
	validator.Required("firstName", firstName, "First name is required")
	validator.Required("lastName", lastName, "Last name is required")
	validator.Required("email", email, "Email is required")
	validator.Email("email", email, "Please enter a valid email address")
	validator.Required("password", password, "Password is required")
	validator.MinLength("password", password, 8, "Password must be at least 8 characters")
	validator.Required("confirmPassword", confirmPassword, "Password confirmation is required")
	
	if password != confirmPassword {
		validator.AddError("confirmPassword", "Passwords do not match")
	}
	
	if !termsAccepted {
		validator.AddError("terms", "You must accept the terms of service")
	}

	if !validator.IsValid() {
		h.handleRegisterError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// In a real application, you would:
	// 1. Check if email/username already exists
	// 2. Hash the password
	// 3. Save user to database
	// 4. Send verification email
	// 5. Create a session or JWT token
	
	log.Printf("Registration attempt: firstName=%s, lastName=%s, email=%s, username=%s", firstName, lastName, email, username)
	log.Printf("HTMX Request: %t", middleware.IsHTMXRequest(r))
	
	// TODO: Integrate with external auth service
	// For now, simulate successful registration
	// In real implementation:
	// 1. Forward validated data to your auth service
	// 2. Handle auth service response
	// 3. Redirect based on success/failure
	
	// Success - handle based on request type
	if middleware.IsHTMXRequest(r) {
		// For HTMX requests, redirect using HX-Redirect header
		// TODO: Change this to appropriate success page or email verification page
		middleware.HTMXRedirect(w, "/auth/login")
	} else {
		// For regular requests, use standard redirect
		// TODO: Change this to appropriate success page or email verification page
		http.Redirect(w, r, "/auth/login", http.StatusSeeOther)
	}
}

// handleLoginError handles login errors for both HTMX and regular requests
func (h *AuthHandler) handleLoginError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
	if middleware.IsHTMXRequest(r) {
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(statusCode)
		
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
		http.Error(w, message, statusCode)
	}
}

// handleRegisterError handles register errors for both HTMX and regular requests
func (h *AuthHandler) handleRegisterError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
	if middleware.IsHTMXRequest(r) {
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(statusCode)
		
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
		http.Error(w, message, statusCode)
	}
}