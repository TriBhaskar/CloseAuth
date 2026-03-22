package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/constants"
	"closeauth-backend-for-frontend/internal/handlers/response"
	"closeauth-backend-for-frontend/internal/middleware"
	sasconfig "closeauth-backend-for-frontend/internal/sas/config"
	"closeauth-backend-for-frontend/internal/sas/service"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// AuthHandler contains dependencies for authentication handlers
type AuthHandler struct {
	endpoints           *config.EndpointsConfig
	authenticatedClient *service.AuthenticatedClient
	logger              *slog.Logger
}

// NewAuthHandler creates a new auth handler instance
func NewAuthHandler() *AuthHandler {
	endpoints, err := config.LoadEndpointsConfig()
	if err != nil {
		slog.Warn("failed to load endpoints config", "error", err)
	}
	
	// Initialize OAuth client config and token manager
	oauthConfig := sasconfig.LoadOAuthClientConfig()
	tokenManager := service.NewTokenManager(oauthConfig)
	authenticatedClient := service.NewAuthenticatedClient(tokenManager, endpoints)
	
	return &AuthHandler{
		endpoints:           endpoints,
		authenticatedClient: authenticatedClient,
		logger:              slog.Default().With("handler", "auth"),
	}
}

// LoginRequest represents the login request payload
// LoginResponse represents the response from auth server
type LoginResponse struct {
	UserID         int    `json:"userId,omitempty"`
	Email          string `json:"email,omitempty"`
	FirstName      string `json:"firstName,omitempty"`
	LastName       string `json:"lastName,omitempty"`
	AccessToken    string `json:"accessToken,omitempty"`
	RefreshToken   string `json:"refreshToken,omitempty"`
	TokenExpiresAt string `json:"tokenExpiresAt,omitempty"` // ISO 8601 format from backend
	TokenType      string `json:"token_type,omitempty"`
	ExpiresIn      int    `json:"expires_in,omitempty"`
	Message        string `json:"message,omitempty"`
	Error          string `json:"error,omitempty"`
}

// RegisterRequest represents the registration request payload
type RegisterRequest struct {
	Username  string `json:"username"`
	Email     string `json:"email"`
	Password  string `json:"password"`
	FirstName string `json:"firstName"`
	LastName  string `json:"lastName"`
	Phone     string `json:"phone,omitempty"`
}

// RegisterResponse represents the response from registration endpoint
type RegisterResponse struct {
	UserID             string `json:"userId,omitempty"`
	Email              string `json:"email,omitempty"`
	FirstName          string `json:"firstName,omitempty"`
	LastName           string `json:"lastName,omitempty"`
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int    `json:"otpValiditySeconds,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
	Error              string `json:"error,omitempty"`
}

// VerifyEmailRequest represents the email verification request payload
type VerifyEmailRequest struct {
	Email            string `json:"email"`
	VerificationCode string `json:"verificationCode"`
}

// VerifyEmailResponse represents the response from verify-email endpoint
type VerifyEmailResponse struct {
	Status    string `json:"status,omitempty"`
	Message   string `json:"message,omitempty"`
	Timestamp string `json:"timestamp,omitempty"`
	Error     string `json:"error,omitempty"`
}

// ResendOTPRequest represents the resend OTP request payload
type ResendOTPRequest struct {
	Email string `json:"email"`
}

// ResendOTPResponse represents the response from resend-otp endpoint
type ResendOTPResponse struct {
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int    `json:"otpValiditySeconds,omitempty"`
	Email              string `json:"email,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
	Error              string `json:"error,omitempty"`
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
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract and validate email
	email := form.GetEmail("email", "Email")

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to send OTP
	// Example:
	// err := h.authService.SendPasswordResetOTP(email)
	// if err != nil {
	//     response.RenderError(w, r, "Failed to send verification code", http.StatusInternalServerError)
	//     return
	// }

	h.logger.Info("password reset requested", "email", email)

	// Get CSRF token for the next form
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Return OTP verification form
	component := templates.OTPVerificationForm(csrfToken, email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleVerifyOTP processes OTP verification
func (h *AuthHandler) HandleVerifyOTP(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract and validate values
	email := form.GetEmail("email", "Email")
	otp := form.GetRequired("otp", "Verification code")

	// Additional OTP validation
	if len(otp) != 6 {
		response.RenderError(w, r, "Verification code must be exactly 6 digits", http.StatusBadRequest)
		return
	}

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to verify OTP
	// Example:
	// token, err := h.authService.VerifyPasswordResetOTP(email, otp)
	// if err != nil {
	//     response.RenderError(w, r, "Invalid or expired verification code", http.StatusUnauthorized)
	//     return
	// }

	h.logger.Info("OTP verification", "email", email, "otp_length", len(otp))

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
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract and validate email
	email := form.GetEmail("email", "Email")

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to resend OTP
	// Example:
	// err := h.authService.ResendPasswordResetOTP(email)
	// if err != nil {
	//     response.RenderError(w, r, "Failed to resend verification code", http.StatusInternalServerError)
	//     return
	// }

	h.logger.Info("resending OTP", "email", email)

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
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract and validate values
	email := form.GetEmail("email", "Email")
	token := form.GetRequired("token", "Reset token")
	password := form.ValidatePasswordStrength("password", "Password")
	confirmPassword := form.GetRequired("confirmPassword", "Confirm Password")

	// Validate password match manually
	if password != "" && password != confirmPassword {
		form.AddError("confirmPassword", "Passwords do not match")
	}

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// TODO: Call your external service to reset password
	// Example:
	// err := h.authService.ResetPassword(email, token, password)
	// if err != nil {
	//     response.RenderError(w, r, "Failed to reset password. Token may be expired.", http.StatusBadRequest)
	//     return
	// }
	
	// Temporary: Use the variables to avoid "declared and not used" error
	_, _, _ = email, token, password

	h.logger.Info("password_reset_successful", "email", email, "token_length", len(token))

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

// HandleLoginGet renders the login form
func (h *AuthHandler) HandleLoginGet(w http.ResponseWriter, r *http.Request) {
	// Check if user is already logged in
	session, err := middleware.GetValidSession(r)
	if err == nil && session != nil {
		// User is already logged in - redirect to dashboard
		h.logger.Info("user_already_logged_in", "email", session.Email, "redirect", constants.RouteAdminDashboard)
		http.Redirect(w, r, constants.RouteAdminDashboard, http.StatusSeeOther)
		return
	}

	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render login template with CSRF token
	component := templates.Login(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleRegisterGet renders the register form
func (h *AuthHandler) HandleRegisterGet(w http.ResponseWriter, r *http.Request) {
	// Check if user is already logged in
	session, err := middleware.GetValidSession(r)
	if err == nil && session != nil {
		// User is already logged in - redirect to dashboard
		h.logger.Info("user_already_logged_in", "email", session.Email, "redirect", constants.RouteAdminDashboard)
		http.Redirect(w, r, constants.RouteAdminDashboard, http.StatusSeeOther)
		return
	}

	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render register template with CSRF token
	component := templates.Register(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleLoginPost processes login form submission
func (h *AuthHandler) HandleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values (form field is "username" but API expects email)
	email := form.GetEmail("username", "Email")
	password := form.GetRequired("password", "Password")
	rememberMe := form.GetBool("remember-me")

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for CloseAuth admin API
	loginRequest := map[string]interface{}{
		"email":    email,
		"password": password,
	}
	
	jsonData, err := json.Marshal(loginRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process login request", http.StatusInternalServerError)
		return
	}

	authURL := h.endpoints.GetAdminLoginURL()
	h.logger.Debug("sending_login_request", "auth_url", authURL)

	// Use authenticated client to make JSON POST request (automatically includes Bearer token)
	resp, err := h.authenticatedClient.PostJSON(context.Background(), authURL, jsonData)
	if err != nil {
		h.logger.Error("auth_service_call_failed", "error", err, "auth_url", authURL)
		response.RenderError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	h.logger.Debug("auth_response_received", "status_code", resp.StatusCode)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process authentication response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("auth_response_body", "status", resp.StatusCode, "body_length", len(body))

	// Check if authentication was successful (either 200 OK or redirect)
	switch resp.StatusCode {
		case http.StatusOK, http.StatusFound, http.StatusSeeOther, http.StatusMovedPermanently:
		// Success - authentication passed
		h.logger.Info("login_successful", "email", email, "remember_me", rememberMe)
		
		// Forward JSESSIONID cookie from Spring to browser
		h.forwardSessionCookies(w, resp)
		
		// Check if there's a redirect location
		redirectURL := resp.Header.Get("Location")
		if redirectURL != "" {
			h.logger.Debug("auth_server_redirect", "redirect_url", redirectURL)
		}
		
		// Try to parse JSON response if available
		var loginResp LoginResponse
		if len(body) > 0 {
			if err := json.Unmarshal(body, &loginResp); err != nil {
				h.logger.Warn("json_parse_failed", "error", err, "body_length", len(body))
			}
		}
		
		// Always create a session on successful login
		// Use access token from response if available, otherwise use a session-based token
		accessToken := loginResp.AccessToken
		if accessToken == "" {
			// No access token returned - use JSESSIONID or generate session identifier
			accessToken = "session-auth"
			h.logger.Debug("no_access_token", "fallback", "session-based-auth")
		} else {
			h.logger.Info("access_token_received", "email", email)
		}
		
		// Calculate token expiry time from tokenExpiresAt or default to 1 hour
		var expiresAt int64
		if loginResp.TokenExpiresAt != "" {
			// Parse ISO 8601 format: "2026-01-06T22:08:22.6943998"
			parsedTime, err := time.Parse("2006-01-02T15:04:05.9999999", loginResp.TokenExpiresAt)
			if err != nil {
				// Try without milliseconds
				parsedTime, err = time.Parse("2006-01-02T15:04:05", loginResp.TokenExpiresAt)
			}
			if err == nil {
				expiresAt = parsedTime.Unix()
				h.logger.Info("token_expiry_parsed", "expires_at", parsedTime.Format(time.RFC3339))
			} else {
				h.logger.Warn("token_expiry_parse_failed", "token_expires_at", loginResp.TokenExpiresAt, "error", err)
				expiresAt = time.Now().Unix() + 3600
			}
		} else if loginResp.ExpiresIn > 0 {
			expiresAt = time.Now().Unix() + int64(loginResp.ExpiresIn)
		} else {
			// Default to 1 hour if not provided
			expiresAt = time.Now().Unix() + 3600
		}
		
		// Create and store session
		session := &middleware.Session{
			UserID:       fmt.Sprintf("%d", loginResp.UserID),
			Email:        email,
			AccessToken:  accessToken,
			RefreshToken: loginResp.RefreshToken,
			ExpiresAt:    expiresAt,
		}
		
		if err := middleware.SetSession(w, session); err != nil {
			h.logger.Error("session_storage_failed", "error", err, "email", email)
			// This is now a problem - redirect to login with error
			response.RenderError(w, r, "Failed to create session. Please try again.", http.StatusInternalServerError)
			return
		}
		h.logger.Info("session_created", "email", email, "expires_at", time.Unix(expiresAt, 0).Format(time.RFC3339))
		
	// Check if there's an OAuth context (user came from OAuth flow)
	// Debug: Print all cookies
	
	oauthCtx, err := middleware.GetOAuthContext(r)
	var finalRedirect string
	
	if err == nil && oauthCtx != nil {
		// OAuth flow - redirect back to authorize endpoint
		h.logger.Info("oauth_context_found", "client_id", oauthCtx.ClientID, "redirect_uri", oauthCtx.RedirectURI, "scope", oauthCtx.Scope)
			// Clear the OAuth context cookie
			middleware.ClearOAuthContext(w)
			
			// Build the authorize URL to continue OAuth flow
			finalRedirect = middleware.BuildAuthorizeURL(oauthCtx, "http://localhost:8088")
			h.logger.Debug("oauth_redirect", "url", finalRedirect)
		} else {
			// Direct login - go to dashboard
			if err != nil {
				h.logger.Debug("no_oauth_context", "error", err)
			}
			finalRedirect = constants.RouteAdminDashboard
		}
		
		// Success - handle based on request type
		if middleware.IsHTMXRequest(r) {
			// For HTMX requests, redirect using HX-Redirect header
			middleware.HTMXRedirect(w, finalRedirect)
		} else {
			// For regular requests, use standard redirect
			http.Redirect(w, r, finalRedirect, http.StatusSeeOther)
		}
	case http.StatusUnauthorized, http.StatusForbidden:
		// Authentication failed
		errorMsg := "Invalid username or password"
		
		// Try to parse error from response
		var loginResp LoginResponse
		if len(body) > 0 && json.Unmarshal(body, &loginResp) == nil {
			if loginResp.Error != "" {
				errorMsg = loginResp.Error
			} else if loginResp.Message != "" {
				errorMsg = loginResp.Message
			}
		}
		
		h.logger.Warn("login_failed", "email", email, "error", errorMsg, "status", resp.StatusCode)
		response.RenderError(w, r, errorMsg, http.StatusUnauthorized)
	default:
		// Unexpected response
		h.logger.Error("unexpected_auth_response", "email", email, "status", resp.StatusCode, "body_length", len(body))
		response.RenderError(w, r, "Authentication failed. Please try again.", http.StatusInternalServerError)
	}
}

// forwardSessionCookies forwards session cookies (JSESSIONID) from Spring to browser
func (h *AuthHandler) forwardSessionCookies(w http.ResponseWriter, resp *http.Response) {
	for _, cookie := range resp.Cookies() {
		h.logger.Debug("forwarding_cookie", "name", cookie.Name, "domain", cookie.Domain)
		
		// Create a new cookie with appropriate settings for BFF
		newCookie := &http.Cookie{
			Name:     cookie.Name,
			Value:    cookie.Value,
			Path:     cookie.Path,
			MaxAge:   cookie.MaxAge,
			Secure:   false, // Set to true in production with HTTPS
			HttpOnly: true,  // Always use HttpOnly for security
			SameSite: http.SameSiteLaxMode,
		}
		
		// Ensure path is set
		if newCookie.Path == "" {
			newCookie.Path = "/"
		}
		
		http.SetCookie(w, newCookie)
	}
}

// HandleRegisterPost processes register form submission
func (h *AuthHandler) HandleRegisterPost(w http.ResponseWriter, r *http.Request) {
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
	termsAccepted := form.GetBool("terms")
	
	// Validate terms acceptance
	if !termsAccepted {
		form.AddError("terms", "You must accept the terms of service")
	}

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for CloseAuth admin API
	registerRequest := RegisterRequest{
		Username:  username,
		Email:     email,
		Password:  password,
		FirstName: firstName,
		LastName:  lastName,
		// Phone is omitted (will be null/empty)
	}

	jsonData, err := json.Marshal(registerRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process registration request", http.StatusInternalServerError)
		return
	}

	registerURL := h.endpoints.GetAdminRegisterURL()
	h.logger.Debug("sending_registration_request", "register_url", registerURL)

	// Use authenticated client to make JSON POST request
	resp, err := h.authenticatedClient.PostJSON(context.Background(), registerURL, jsonData)
	if err != nil {
		h.logger.Error("registration_service_call_failed", "error", err, "register_url", registerURL)
		response.RenderError(w, r, "Registration service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process registration response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("registration_response", "status", resp.StatusCode, "body_length", len(body))

	// Parse response
	var registerResp RegisterResponse
	if len(body) > 0 {
		if err := json.Unmarshal(body, &registerResp); err != nil {
			h.logger.Warn("json_parse_failed", "error", err)
		}
	}

	// Handle response based on status code
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated:
		// Success - registration initiated, OTP sent
		h.logger.Info("registration_initiated", "email", email, "otp_validity_seconds", registerResp.OTPValiditySeconds)

		// Get CSRF token for the OTP form
		csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

		// Return OTP form to be inserted into the form container
		if middleware.IsHTMXRequest(r) {
			// Return the OTP form content that replaces the registration form
			component := templates.RegisterOTPForm(csrfToken, email)
			w.Header().Set("Content-Type", "text/html")
			w.WriteHeader(http.StatusOK)
			templ.Handler(component).ServeHTTP(w, r)
		} else {
			// For regular requests, redirect to a verification page
			http.Redirect(w, r, "/auth/verify?email="+email, http.StatusSeeOther)
		}

	case http.StatusBadRequest:
		// Validation error or user already exists
		errorMsg := "Registration failed. Please check your information."
		if registerResp.Error != "" {
			errorMsg = registerResp.Error
		} else if registerResp.Message != "" {
			errorMsg = registerResp.Message
		}
		h.logger.Warn("registration_validation_failed", "email", email, "error", errorMsg)
		response.RenderError(w, r, errorMsg, http.StatusBadRequest)

	case http.StatusConflict:
		// User already exists
		errorMsg := "An account with this email already exists"
		if registerResp.Message != "" {
			errorMsg = registerResp.Message
		}
		h.logger.Warn("registration_conflict", "email", email, "error", errorMsg)
		response.RenderError(w, r, errorMsg, http.StatusConflict)

	default:
		// Unexpected response
		h.logger.Error("unexpected_registration_response", "email", email, "status", resp.StatusCode, "body_length", len(body))
		errorMsg := "Registration failed. Please try again."
		if registerResp.Error != "" {
			errorMsg = registerResp.Error
		} else if registerResp.Message != "" {
			errorMsg = registerResp.Message
		}
		response.RenderError(w, r, errorMsg, http.StatusInternalServerError)
	}
}

// HandleRegisterOTP renders the OTP verification page for admin registration.
func (h *AuthHandler) HandleRegisterOTP(w http.ResponseWriter, r *http.Request) {
	email := r.URL.Query().Get("email")
	if email == "" {
		response.RenderError(w, r, "Email is required", http.StatusBadRequest)
		return
	}

	// Get CSRF token for the OTP form
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Return the OTP form
	component := templates.RegisterOTPForm(csrfToken, email)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleVerifyRegistrationOTP processes OTP verification for registration
func (h *AuthHandler) HandleVerifyRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract values
	email := form.GetEmail("email", "Email")
	otp := form.GetRequired("otp", "Verification code")
	
	// Validate OTP length
	if otp != "" && len(otp) != 6 {
		form.AddError("otp", "Verification code must be exactly 6 digits")
	}

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for verify-email endpoint
	verifyRequest := VerifyEmailRequest{
		Email:            email,
		VerificationCode: otp,
	}

	jsonData, err := json.Marshal(verifyRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process verification request", http.StatusInternalServerError)
		return
	}

	verifyURL := h.endpoints.GetAdminVerifyEmailURL()
	h.logger.Debug("sending_email_verification", "verify_url", verifyURL)

	// Use authenticated client to make JSON POST request
	resp, err := h.authenticatedClient.PostJSON(context.Background(), verifyURL, jsonData)
	if err != nil {
		h.logger.Error("verify_email_service_call_failed", "error", err, "verify_url", verifyURL)
		response.RenderError(w, r, "Verification service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process verification response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("verify_email_response", "status", resp.StatusCode, "body_length", len(body))

	// Parse response
	var verifyResp VerifyEmailResponse
	if len(body) > 0 {
		if err := json.Unmarshal(body, &verifyResp); err != nil {
			h.logger.Warn("json_parse_failed", "error", err)
		}
	}

	// Handle response based on status code
	switch resp.StatusCode {
	case http.StatusOK:
		// Success - email verified, registration complete
		h.logger.Info("email_verification_successful", "email", email)

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
					window.location.href = '` + constants.RouteAdminLogin + `';
				}, 2000);
			</script>`
			w.Header().Set("Content-Type", "text/html")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(successHTML))
		} else {
			http.Redirect(w, r, constants.RouteAdminLogin+"?registered=success", http.StatusSeeOther)
		}

	case http.StatusBadRequest, http.StatusUnauthorized:
		// Invalid or expired OTP
		errorMsg := "Invalid or expired verification code"
		if verifyResp.Error != "" {
			errorMsg = verifyResp.Error
		} else if verifyResp.Message != "" {
			errorMsg = verifyResp.Message
		}
		h.logger.Warn("email_verification_failed", "email", email, "error", errorMsg)
		response.RenderError(w, r, errorMsg, http.StatusBadRequest)

	default:
		// Unexpected response
		h.logger.Error("unexpected_verify_email_response", "email", email, "status", resp.StatusCode, "body_length", len(body))
		errorMsg := "Verification failed. Please try again."
		if verifyResp.Error != "" {
			errorMsg = verifyResp.Error
		} else if verifyResp.Message != "" {
			errorMsg = verifyResp.Message
		}
		response.RenderError(w, r, errorMsg, http.StatusInternalServerError)
	}
}

// HandleResendRegistrationOTP resends the OTP to the user's email during registration
func (h *AuthHandler) HandleResendRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract and validate email
	email := form.GetEmail("email", "Email")

	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for resend-otp endpoint
	resendRequest := ResendOTPRequest{
		Email: email,
	}

	jsonData, err := json.Marshal(resendRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process resend request", http.StatusInternalServerError)
		return
	}

	resendURL := h.endpoints.GetAdminResendOTPURL()
	h.logger.Debug("sending_resend_otp", "resend_url", resendURL)

	// Use authenticated client to make JSON POST request
	resp, err := h.authenticatedClient.PostJSON(context.Background(), resendURL, jsonData)
	if err != nil {
		h.logger.Error("resend_otp_service_call_failed", "error", err, "resend_url", resendURL)
		response.RenderError(w, r, "Failed to resend verification code. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process resend response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("resend_otp_response", "status", resp.StatusCode, "body_length", len(body))

	// Parse response
	var resendResp ResendOTPResponse
	if len(body) > 0 {
		if err := json.Unmarshal(body, &resendResp); err != nil {
			h.logger.Warn("json_parse_failed", "error", err)
		}
	}

	// Handle response based on status code
	switch resp.StatusCode {
	case http.StatusOK:
		// Success - OTP resent
		h.logger.Info("otp_resent_successful", "email", email, "otp_validity_seconds", resendResp.OTPValiditySeconds)

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

	case http.StatusTooManyRequests:
		// Rate limited
		errorMsg := "Please wait before requesting another code"
		if resendResp.Message != "" {
			errorMsg = resendResp.Message
		}
		h.logger.Warn("resend_otp_rate_limited", "email", email, "error", errorMsg)
		response.RenderError(w, r, errorMsg, http.StatusTooManyRequests)

	default:
		// Unexpected response
		h.logger.Error("unexpected_resend_otp_response", "email", email, "status", resp.StatusCode, "body_length", len(body))
		errorMsg := "Failed to resend verification code. Please try again."
		if resendResp.Error != "" {
			errorMsg = resendResp.Error
		} else if resendResp.Message != "" {
			errorMsg = resendResp.Message
		}
		response.RenderError(w, r, errorMsg, http.StatusInternalServerError)
	}
}

// handleLoginError handles login errors for both HTMX and regular requests
// HandleLogout clears the user session and redirects to home page
func (h *AuthHandler) HandleLogout(w http.ResponseWriter, r *http.Request) {
	// Clear the session cookie
	middleware.ClearSession(w)
	
	h.logger.Info("user_logged_out")
	
	// Set no-cache headers to prevent browser back button issues
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Expires", "0")
	
	// Redirect to home page
	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, constants.RouteHome)
	} else {
		http.Redirect(w, r, constants.RouteHome, http.StatusSeeOther)
	}
}

// AdminAuthHandler contains dependencies for admin authentication handlers
type AdminAuthHandler struct {
	endpoints           *config.EndpointsConfig
	authenticatedClient *service.AuthenticatedClient
	logger              *slog.Logger
}

// NewAdminAuthHandler creates a new admin auth handler instance
func NewAdminAuthHandler() *AdminAuthHandler {
	endpoints, err := config.LoadEndpointsConfig()
	if err != nil {
		slog.Warn("failed to load endpoints config", "error", err)
	}
	
	// Initialize OAuth client config and token manager
	oauthConfig := sasconfig.LoadOAuthClientConfig()
	tokenManager := service.NewTokenManager(oauthConfig)
	authenticatedClient := service.NewAuthenticatedClient(tokenManager, endpoints)
	
	return &AdminAuthHandler{
		endpoints:           endpoints,
		authenticatedClient: authenticatedClient,
		logger:              slog.Default().With("handler", "admin_auth"),
	}
}

// HandleAdminLoginGet renders the admin login form
func (h *AdminAuthHandler) HandleAdminLoginGet(w http.ResponseWriter, r *http.Request) {
	// Get CSRF token from context
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	
	// Render admin login template with CSRF token
	component := templates.Login(csrfToken)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleAdminLoginPost processes admin login form submission
func (h *AdminAuthHandler) HandleAdminLoginPost(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values (form field is "username" but API expects email)
	email := form.GetEmail("username", "Email")
	password := form.GetRequired("password", "Password")
	rememberMe := form.GetBool("remember-me")

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for CloseAuth admin API
	loginRequest := map[string]interface{}{
		"email":    email,
		"password": password,
	}
	
	jsonData, err := json.Marshal(loginRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process login request", http.StatusInternalServerError)
		return
	}

	authURL := h.endpoints.GetAdminLoginURL()
	h.logger.Debug("sending_admin_login_request", "auth_url", authURL)

	// Use authenticated client to make JSON POST request (automatically includes Bearer token)
	resp, err := h.authenticatedClient.PostJSON(context.Background(), authURL, jsonData)
	if err != nil {
		h.logger.Error("admin_auth_service_call_failed", "error", err, "auth_url", authURL)
		response.RenderError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	h.logger.Debug("admin_auth_response_received", "status_code", resp.StatusCode)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process authentication response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("admin_auth_response_body", "status", resp.StatusCode, "body_length", len(body))

	// Check if authentication was successful (either 200 OK or redirect)
	switch resp.StatusCode {
		case http.StatusOK, http.StatusFound, http.StatusSeeOther, http.StatusMovedPermanently:
		// Success - authentication passed
		h.logger.Info("admin_login_successful", "email", email, "remember_me", rememberMe)
		
		// TODO: Forward JSESSIONID cookie from Spring to browser if needed
		
		// Check if there's a redirect location
		redirectURL := resp.Header.Get("Location")
		if redirectURL != "" {
			h.logger.Debug("admin_auth_server_redirect", "redirect_url", redirectURL)
		}
		
		// Try to parse JSON response if available
		var loginResp LoginResponse
		if len(body) > 0 {
			if err := json.Unmarshal(body, &loginResp); err != nil {
				h.logger.Warn("json_parse_failed", "error", err, "body_length", len(body))
			}
		}
		
		// Always create a session on successful login
		// Use access token from response if available, otherwise use a session-based token
		accessToken := loginResp.AccessToken
		if accessToken == "" {
			// No access token returned - use JSESSIONID or generate session identifier
			accessToken = "session-auth"
			h.logger.Debug("no_access_token", "fallback", "session-based-auth")
		} else {
			h.logger.Info("access_token_received", "email", email)
		}
		
		// Calculate token expiry time from tokenExpiresAt or default to 1 hour
		var expiresAt int64
		if loginResp.TokenExpiresAt != "" {
			// Parse ISO 8601 format: "2026-01-06T22:08:22.6943998"
			parsedTime, err := time.Parse("2006-01-02T15:04:05.9999999", loginResp.TokenExpiresAt)
			if err != nil {
				// Try without milliseconds
				parsedTime, err = time.Parse("2006-01-02T15:04:05", loginResp.TokenExpiresAt)
			}
			if err == nil {
				expiresAt = parsedTime.Unix()
				h.logger.Info("token_expiry_parsed", "expires_at", parsedTime.Format(time.RFC3339))
			} else {
				h.logger.Warn("token_expiry_parse_failed", "token_expires_at", loginResp.TokenExpiresAt, "error", err)
				expiresAt = time.Now().Unix() + 3600
			}
		} else if loginResp.ExpiresIn > 0 {
			expiresAt = time.Now().Unix() + int64(loginResp.ExpiresIn)
		} else {
			// Default to 1 hour if not provided
			expiresAt = time.Now().Unix() + 3600
		}
		
		// Create and store session
		session := &middleware.Session{
			UserID:       fmt.Sprintf("%d", loginResp.UserID),
			Email:        email,
			AccessToken:  accessToken,
			RefreshToken: loginResp.RefreshToken,
			ExpiresAt:    expiresAt,
		}
		
		if err := middleware.SetSession(w, session); err != nil {
			h.logger.Error("session_storage_failed", "error", err, "email", email)
			// This is now a problem - redirect to login with error
			response.RenderError(w, r, "Failed to create session. Please try again.", http.StatusInternalServerError)
			return
		}
		h.logger.Info("session_created", "email", email, "expires_at", time.Unix(expiresAt, 0).Format(time.RFC3339))
		
	// Check if there's an OAuth context (user came from OAuth flow)
	// Debug: Print all cookies
	
	oauthCtx, err := middleware.GetOAuthContext(r)
	var finalRedirect string
	
	if err == nil && oauthCtx != nil {
		// OAuth flow - redirect back to authorize endpoint
		h.logger.Info("oauth_context_found", "client_id", oauthCtx.ClientID, "redirect_uri", oauthCtx.RedirectURI, "scope", oauthCtx.Scope)
			// Clear the OAuth context cookie
			middleware.ClearOAuthContext(w)
			
			// Build the authorize URL to continue OAuth flow
			finalRedirect = middleware.BuildAuthorizeURL(oauthCtx, "http://localhost:8088")
			h.logger.Debug("oauth_redirect", "url", finalRedirect)
		} else {
			// Direct login - go to dashboard
			if err != nil {
				h.logger.Debug("no_oauth_context", "error", err)
			}
			finalRedirect = constants.RouteAdminDashboard
		}
		
		// Success - handle based on request type
		if middleware.IsHTMXRequest(r) {
			// For HTMX requests, redirect using HX-Redirect header
			middleware.HTMXRedirect(w, finalRedirect)
		} else {
			// For regular requests, use standard redirect
			http.Redirect(w, r, finalRedirect, http.StatusSeeOther)
		}
	case http.StatusUnauthorized, http.StatusForbidden:
		// Authentication failed
		errorMsg := "Invalid username or password"
		
		// Try to parse error from response
		var loginResp LoginResponse
		if len(body) > 0 && json.Unmarshal(body, &loginResp) == nil {
			if loginResp.Error != "" {
				errorMsg = loginResp.Error
			} else if loginResp.Message != "" {
				errorMsg = loginResp.Message
			}
		}
		
		h.logger.Warn("admin_login_failed", "email", email, "error", errorMsg, "status", resp.StatusCode)
		response.RenderError(w, r, errorMsg, http.StatusUnauthorized)
	default:
		// Unexpected response
		h.logger.Error("unexpected_admin_auth_response", "email", email, "status", resp.StatusCode, "body_length", len(body))
		response.RenderError(w, r, "Authentication failed. Please try again.", http.StatusInternalServerError)
	}
}
