package admin

import (
	"net/http"

	"closeauth-backend-for-frontend/internal/handlers/response"
	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

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
