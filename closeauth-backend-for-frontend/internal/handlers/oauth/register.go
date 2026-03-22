package oauth

import (
	"net/http"

	"closeauth-backend-for-frontend/internal/handlers/response"
	"closeauth-backend-for-frontend/internal/middleware"
	"closeauth-backend-for-frontend/internal/templates/components/auth"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// HandleOAuthRegisterGet renders the client-themed registration page
func (h *OAuthClientAuthHandler) HandleOAuthRegisterGet(w http.ResponseWriter, r *http.Request) {
	clientID := r.URL.Query().Get("client_id")
	continueURL := r.URL.Query().Get("continue")

	// Try to get OAuth context from cookie if client_id not in query
	if clientID == "" {
		oauthCtx, err := middleware.GetOAuthContext(r)
		if err == nil && oauthCtx != nil {
			clientID = oauthCtx.ClientID
		}
	}

	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Fetch client theme from database
	themeData := h.getClientTheme(r, clientID)

	// Build register data
	registerData := templates.OAuthRegisterData{
		CSRFToken:   csrfToken,
		Theme:       convertThemeToThemeData(themeData),
		ClientName:  h.getClientName(clientID),
		ErrorMsg:    "",
		ContinueURL: continueURL,
	}

	// Render the OAuth register template
	component := templates.OAuthRegister(registerData)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthRegisterPost processes the client-themed registration form submission
func (h *OAuthClientAuthHandler) HandleOAuthRegisterPost(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	firstName := form.GetRequired("firstName", "First name")
	lastName := form.GetRequired("lastName", "Last name")
	email := form.GetEmail("email", "Email")
	username := form.GetRequired("username", "Username")
	password, _ := form.ValidatePasswordMatch("password", "confirmPassword", "Password")
	// Validate password strength
	if password != "" && len(password) < 8 {
		form.AddError("password", "Password must be at least 8 characters")
	}
	clientID := form.Get("client_id")

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	h.logger.Info("OAuth client registration request", "email", email, "client_id", clientID, "username", username, "first_name", firstName, "last_name", lastName)

	// TODO: Call your external service to register user and send OTP
	// Example:
	// err := h.authService.RegisterUser(firstName, lastName, email, username, password)
	// if err != nil {
	//     response.RenderError(w, r, "Registration failed", http.StatusInternalServerError)
	//     return
	// }

	// Get CSRF token and theme for OTP dialog
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	themeData := h.getClientTheme(r, clientID)

	// Return OTP verification dialog
	component := auth.OAuthOTPDialog(csrfToken, email, clientID, convertThemeToThemeData(themeData))
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthVerifyRegistrationOTP verifies the registration OTP
func (h *OAuthClientAuthHandler) HandleOAuthVerifyRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	email := form.GetEmail("email", "Email")
	otp := form.GetRequired("otp", "Verification code")
	clientID := form.Get("client_id")

	// Validate OTP length
	if otp != "" && len(otp) != 6 {
		form.AddError("otp", "Verification code must be exactly 6 digits")
	}

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	h.logger.Info("OAuth registration OTP verification", "email", email, "client_id", clientID)

	// TODO: Verify OTP with your auth service
	// Example:
	// err := h.authService.VerifyRegistrationOTP(email, otp)
	// if err != nil {
	//     response.RenderError(w, r, "Invalid or expired verification code", http.StatusUnauthorized)
	//     return
	// }

	// After successful verification, redirect to login
	redirectURL := "/oauth/login?client_id=" + clientID + "&registered=true"

	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, redirectURL)
	} else {
		http.Redirect(w, r, redirectURL, http.StatusSeeOther)
	}
}

// HandleOAuthResendRegistrationOTP resends the registration OTP
func (h *OAuthClientAuthHandler) HandleOAuthResendRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	if err := r.ParseForm(); err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	email := r.FormValue("email")
	clientID := r.FormValue("client_id")

	h.logger.Info("resending OAuth registration OTP", "email", email, "client_id", clientID)

	// TODO: Resend OTP with your auth service
	// Example:
	// err := h.authService.ResendRegistrationOTP(email)
	// if err != nil {
	//     h.handleResendError(w, r, "Failed to resend verification code")
	//     return
	// }

	// Return success message
	w.Header().Set("Content-Type", "text/html")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`<span class="text-green-600 dark:text-green-400 text-sm">Verification code resent successfully!</span>`))
}

// getClientName fetches the client name for a given client ID
func (h *OAuthClientAuthHandler) getClientName(clientID string) string {
	if clientID == "" {
		return "Application"
	}
	// In a real scenario, you would fetch this from a database or a service
	return "Application"
}
