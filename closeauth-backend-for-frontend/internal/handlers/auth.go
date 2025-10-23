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

// HandleForgotPasswordGet renders the forgot password form
func (h *AuthHandler) HandleForgotPasswordGet(w http.ResponseWriter, r *http.Request) {
	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render forgot password template with CSRF token
	component := templates.ForgotPassword(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleForgotPasswordRequest processes the initial email submission and sends OTP
func (h *AuthHandler) HandleForgotPasswordRequest(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleForgotPasswordError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract email
	email := r.FormValue("email")

	// Validate email
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Email("email", email, "Please enter a valid email address")

	if !validator.IsValid() {
		h.handleForgotPasswordError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to send OTP
	// Example:
	// err := h.authService.SendPasswordResetOTP(email)
	// if err != nil {
	//     h.handleForgotPasswordError(w, r, "Failed to send verification code", http.StatusInternalServerError)
	//     return
	// }

	log.Printf("Password reset requested for email: %s", email)

	// Get CSRF token for the next form
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Return OTP verification form
	component := templates.OTPVerificationForm(csrfToken, email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleVerifyOTP processes OTP verification
func (h *AuthHandler) HandleVerifyOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleForgotPasswordError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract values
	email := r.FormValue("email")
	otp := r.FormValue("otp")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Required("otp", otp, "Verification code is required")
	validator.MinLength("otp", otp, 6, "Verification code must be 6 digits")
	
	if len(otp) != 6 {
		validator.AddError("otp", "Verification code must be exactly 6 digits")
	}

	if !validator.IsValid() {
		h.handleForgotPasswordError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to verify OTP
	// Example:
	// token, err := h.authService.VerifyPasswordResetOTP(email, otp)
	// if err != nil {
	//     h.handleForgotPasswordError(w, r, "Invalid or expired verification code", http.StatusUnauthorized)
	//     return
	// }

	log.Printf("OTP verification for email: %s, OTP: %s", email, otp)

	// For now, generate a temporary token (replace with actual token from your service)
	token := "temp-reset-token-" + email

	// Get CSRF token for the next form
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Return password reset form
	component := templates.ResetPasswordForm(csrfToken, email, token)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleResendOTP resends the OTP to the user's email
func (h *AuthHandler) HandleResendOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleForgotPasswordError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract email
	email := r.FormValue("email")

	// Validate email
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")

	if !validator.IsValid() {
		h.handleForgotPasswordError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to resend OTP
	// Example:
	// err := h.authService.ResendPasswordResetOTP(email)
	// if err != nil {
	//     h.handleForgotPasswordError(w, r, "Failed to resend verification code", http.StatusInternalServerError)
	//     return
	// }

	log.Printf("Resending OTP for email: %s", email)

	// Return success message
	successHTML := `<div class="mb-4 p-3 rounded-md bg-green-50 border border-green-200">
		<div class="flex">
			<div class="flex-shrink-0">
				<svg class="h-5 w-5 text-green-400" fill="currentColor" viewBox="0 0 20 20">
					<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
				</svg>
			</div>
			<div class="ml-3">
				<p class="text-sm text-green-800">Verification code resent successfully</p>
			</div>
		</div>
	</div>`
	w.Header().Set("Content-Type", "text/html")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(successHTML))
}

// HandleResetPassword processes the final password reset
func (h *AuthHandler) HandleResetPassword(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleForgotPasswordError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract values
	email := r.FormValue("email")
	token := r.FormValue("token")
	password := r.FormValue("password")
	confirmPassword := r.FormValue("confirmPassword")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Required("token", token, "Invalid reset token")
	validator.Required("password", password, "Password is required")
	validator.MinLength("password", password, 8, "Password must be at least 8 characters")
	validator.Required("confirmPassword", confirmPassword, "Password confirmation is required")

	if password != confirmPassword {
		validator.AddError("confirmPassword", "Passwords do not match")
	}

	if !validator.IsValid() {
		h.handleForgotPasswordError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to reset password
	// Example:
	// err := h.authService.ResetPassword(email, token, password)
	// if err != nil {
	//     h.handleForgotPasswordError(w, r, "Failed to reset password. Token may be expired.", http.StatusBadRequest)
	//     return
	// }

	log.Printf("Password reset successful for email: %s", email)

	// Return success message and redirect
	if middleware.IsHTMXRequest(r) {
		// Success message then redirect after a delay
		successHTML := `<div class="mb-4 p-3 rounded-md bg-green-50 border border-green-200">
			<div class="flex">
				<div class="flex-shrink-0">
					<svg class="h-5 w-5 text-green-400" fill="currentColor" viewBox="0 0 20 20">
						<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
					</svg>
				</div>
				<div class="ml-3">
					<p class="text-sm text-green-800">Password reset successful! Redirecting to login...</p>
				</div>
			</div>
		</div>
		<script>
			setTimeout(function() {
				window.location.href = '/auth/login';
			}, 2000);
		</script>`
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(successHTML))
	} else {
		http.Redirect(w, r, "/auth/login?reset=success", http.StatusSeeOther)
	}
}

// handleForgotPasswordError handles forgot password errors for both HTMX and regular requests
func (h *AuthHandler) handleForgotPasswordError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
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
	
	// TODO: Integrate with external auth service to send OTP
	// For now, simulate sending OTP
	// In real implementation:
	// 1. Forward validated data to your auth service
	// 2. Auth service creates user (pending verification)
	// 3. Auth service sends OTP to email
	// 4. Return success and trigger OTP dialog
	
	// Get CSRF token for the OTP form
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Return OTP form to be inserted into the dialog
	if middleware.IsHTMXRequest(r) {
		// Return the OTP form content
		component := templates.RegisterOTPForm(csrfToken, email)
		w.Header().Set("Content-Type", "text/html")
		
		// Add custom header to trigger dialog opening
		w.Header().Set("HX-Trigger", "openOTPDialog")
		
		// Render the OTP form to be inserted into #otp-dialog-content
		w.WriteHeader(http.StatusOK)
		templ.Handler(component).ServeHTTP(w, r)
	} else {
		// For regular requests, redirect to a verification page
		http.Redirect(w, r, "/auth/verify?email="+email, http.StatusSeeOther)
	}
}

// HandleVerifyRegistrationOTP processes OTP verification for registration
func (h *AuthHandler) HandleVerifyRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract values
	email := r.FormValue("email")
	otp := r.FormValue("otp")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Required("otp", otp, "Verification code is required")
	validator.MinLength("otp", otp, 6, "Verification code must be 6 digits")
	
	if len(otp) != 6 {
		validator.AddError("otp", "Verification code must be exactly 6 digits")
	}

	if !validator.IsValid() {
		h.handleRegisterError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to verify OTP and complete registration
	// Example:
	// err := h.authService.VerifyRegistrationOTP(email, otp)
	// if err != nil {
	//     h.handleRegisterError(w, r, "Invalid or expired verification code", http.StatusUnauthorized)
	//     return
	// }

	log.Printf("Registration OTP verification for email: %s, OTP: %s", email, otp)

	// Success - registration complete
	if middleware.IsHTMXRequest(r) {
		// Return success message and trigger redirect
		successHTML := `<div class="mb-4 p-3 rounded-md bg-green-50 border border-green-200">
			<div class="flex">
				<div class="flex-shrink-0">
					<svg class="h-5 w-5 text-green-400" fill="currentColor" viewBox="0 0 20 20">
						<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
					</svg>
				</div>
				<div class="ml-3">
					<p class="text-sm text-green-800">Registration successful! Redirecting to login...</p>
				</div>
			</div>
		</div>
		<script>
			setTimeout(function() {
				window.location.href = '/auth/login';
			}, 2000);
		</script>`
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(successHTML))
	} else {
		http.Redirect(w, r, "/auth/login?registered=success", http.StatusSeeOther)
	}
}

// HandleResendRegistrationOTP resends the OTP to the user's email during registration
func (h *AuthHandler) HandleResendRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	err := r.ParseForm()
	if err != nil {
		h.handleRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract email
	email := r.FormValue("email")

	// Validate email
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")

	if !validator.IsValid() {
		h.handleRegisterError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to resend registration OTP
	// Example:
	// err := h.authService.ResendRegistrationOTP(email)
	// if err != nil {
	//     h.handleRegisterError(w, r, "Failed to resend verification code", http.StatusInternalServerError)
	//     return
	// }

	log.Printf("Resending registration OTP for email: %s", email)

	// Return success message
	successHTML := `<div class="mb-4 p-3 rounded-md bg-green-50 border border-green-200">
		<div class="flex">
			<div class="flex-shrink-0">
				<svg class="h-5 w-5 text-green-400" fill="currentColor" viewBox="0 0 20 20">
					<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
				</svg>
			</div>
			<div class="ml-3">
				<p class="text-sm text-green-800">Verification code resent successfully</p>
			</div>
		</div>
	</div>`
	w.Header().Set("Content-Type", "text/html")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(successHTML))
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