package admin

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"

	"closeauth-backend-for-frontend/internal/constants"
	"closeauth-backend-for-frontend/internal/handlers/response"
	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

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

// HandleRegisterPost processes registration form submission
func (h *AuthHandler) HandleRegisterPost(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	email := form.GetEmail("email", "Email")
	password := form.ValidatePasswordStrength("password", "Password")
	confirmPassword := form.GetRequired("confirmPassword", "Confirm Password")
	firstName := form.GetRequired("firstName", "First Name")
	lastName := form.GetRequired("lastName", "Last Name")

	// Manual validation for password match
	if password != "" && password != confirmPassword {
		form.AddError("confirmPassword", "Passwords do not match")
	}

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for registration
	registerRequest := RegisterRequest{
		Username:  email, // Use email as username
		Email:     email,
		Password:  password,
		FirstName: firstName,
		LastName:  lastName,
	}

	jsonData, err := json.Marshal(registerRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process registration request", http.StatusInternalServerError)
		return
	}

		registerURL := h.endpoints.GetAdminRegisterURL()
	h.logger.Debug("sending_register_request", "register_url", registerURL)

	// Make POST request to registration endpoint
	resp, err := http.Post(registerURL, "application/json", bytes.NewBuffer(jsonData))
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

	h.logger.Debug("registration_response_body", "status", resp.StatusCode, "body_length", len(body))

	// Handle response
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated:
		var regResp RegisterResponse
		if err := json.Unmarshal(body, &regResp); err != nil {
			h.logger.Warn("json_parse_failed", "error", err, "body_length", len(body))
			response.RenderError(w, r, "Registration successful, but failed to parse response.", http.StatusOK)
			return
		}

		h.logger.Info("registration_successful", "email", email)

		// Get CSRF token for the OTP form
		csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

		// Render OTP verification form
		component := templates.OTPVerificationForm(csrfToken, email)
		templ.Handler(component).ServeHTTP(w, r)

	case http.StatusBadRequest:
		var errResp RegisterResponse
		if json.Unmarshal(body, &errResp) == nil && errResp.Error != "" {
			h.logger.Warn("registration_bad_request", "email", email, "error", errResp.Error)
			response.RenderError(w, r, errResp.Error, http.StatusBadRequest)
		} else {
			h.logger.Warn("registration_bad_request_unparsed", "email", email, "status_code", resp.StatusCode)
			response.RenderError(w, r, "Invalid registration data provided.", http.StatusBadRequest)
		}

	default:
		h.logger.Error("unhandled_registration_error", "email", email, "status_code", resp.StatusCode)
		response.RenderError(w, r, "An unexpected error occurred during registration.", resp.StatusCode)
	}
}

// HandleVerifyRegistrationOTP processes the OTP submitted after registration
func (h *AuthHandler) HandleVerifyRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	email := form.GetEmail("email", "Email")
	otp := form.GetRequired("otp", "Verification Code")

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Prepare JSON payload for OTP verification
	verifyRequest := VerifyEmailRequest{
		Email:            email,
		VerificationCode: otp,
	}

	jsonData, err := json.Marshal(verifyRequest)
	if err != nil {
		h.logger.Error("json_marshal_failed", "error", err)
		response.RenderError(w, r, "Failed to process OTP verification request", http.StatusInternalServerError)
		return
	}

		verifyURL := h.endpoints.GetAdminVerifyEmailURL()
	h.logger.Debug("sending_otp_verification_request", "verify_url", verifyURL)

	// Make POST request to verification endpoint
	resp, err := http.Post(verifyURL, "application/json", bytes.NewBuffer(jsonData))
	if err != nil {
		h.logger.Error("otp_verification_service_call_failed", "error", err, "verify_url", verifyURL)
		response.RenderError(w, r, "OTP verification service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		h.logger.Error("response_read_failed", "error", err)
		response.RenderError(w, r, "Failed to process OTP verification response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("otp_verification_response_body", "status", resp.StatusCode, "body_length", len(body))

	// Handle response
	switch resp.StatusCode {
	case http.StatusOK:
		var verifyResp VerifyEmailResponse
		if err := json.Unmarshal(body, &verifyResp); err != nil {
			h.logger.Warn("json_parse_failed", "error", err, "body_length", len(body))
		}

		h.logger.Info("otp_verification_successful", "email", email, "message", verifyResp.Message)

		// On success, redirect to the login page with a success message
		if middleware.IsHTMXRequest(r) {
			// For HTMX, you might show a success message and then redirect
			w.Header().Set("HX-Redirect", constants.RouteAdminLogin+"?verified=true")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte("Email verified successfully! Redirecting to login..."))
		} else {
			http.Redirect(w, r, constants.RouteAdminLogin+"?verified=true", http.StatusSeeOther)
		}

	case http.StatusBadRequest:
		var errResp VerifyEmailResponse
		if json.Unmarshal(body, &errResp) == nil && errResp.Error != "" {
			h.logger.Warn("otp_verification_bad_request", "email", email, "error", errResp.Error)
			response.RenderError(w, r, errResp.Error, http.StatusBadRequest)
		} else {
			h.logger.Warn("otp_verification_bad_request_unparsed", "email", email, "status_code", resp.StatusCode)
			response.RenderError(w, r, "Invalid or expired verification code.", http.StatusBadRequest)
		}

	default:
		h.logger.Error("unhandled_otp_verification_error", "email", email, "status_code", resp.StatusCode)
		response.RenderError(w, r, "An unexpected error occurred during OTP verification.", resp.StatusCode)
	}
}
