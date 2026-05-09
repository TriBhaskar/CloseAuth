package server

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"closeauth-frontend/internal/middleware"
	"closeauth-frontend/internal/spring"
)

// ──────────────────────────────────────────────────────────────────────────────
// Admin Authentication Handlers (JSON API for SPA fetch)
// ──────────────────────────────────────────────────────────────────────────────

// handleAdminLoginImpl processes admin login and sets an encrypted session cookie.
func (s *Server) handleAdminLoginImpl(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "admin_login")

	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if req.Username == "" || req.Password == "" {
		jsonError(w, "Username and password are required", http.StatusBadRequest)
		return
	}

	// Proxy to Spring admin login endpoint
	jsonBody, _ := json.Marshal(map[string]string{
		"email":    req.Username,
		"password": req.Password,
	})

	logger.Info("attempting admin login:", s.springConfig.AdminLoginURL())
	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminLoginURL(), jsonBody)
	if err != nil {
		logger.Error("admin login proxy failed", "error", err)
		jsonError(w, "Authentication service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Parse Spring response
	var loginResp spring.LoginResponse
	if err := json.Unmarshal(result.Body, &loginResp); err != nil {
		logger.Error("failed to parse login response", "error", err)
		jsonError(w, "Authentication failed", http.StatusInternalServerError)
		return
	}

	// Handle failure
	if result.StatusCode == http.StatusUnauthorized || result.StatusCode == http.StatusForbidden {
		errorMsg := "Invalid username or password"
		if loginResp.Error != "" {
			errorMsg = loginResp.Error
		} else if loginResp.Message != "" {
			errorMsg = loginResp.Message
		}
		jsonError(w, errorMsg, http.StatusUnauthorized)
		return
	}

	if result.StatusCode != http.StatusOK {
		logger.Error("unexpected admin login response", "status", result.StatusCode, "msg", result.Body)
		jsonError(w, "Authentication failed", http.StatusNotFound)
		return
	}

	// Login successful — set encrypted session cookie
	session := &middleware.Session{
		UserID:       fmt.Sprintf("%d", loginResp.UserID),
		Email:        loginResp.Email,
		Username:     req.Username,
		Role:         "Admin",
		AccessToken:  loginResp.AccessToken,
		RefreshToken: loginResp.RefreshToken,
		ExpiresAt:    time.Now().Add(24 * time.Hour).Unix(),
	}

	if err := middleware.SetSession(w, session, s.springConfig.IsProduction()); err != nil {
		logger.Error("failed to set session", "error", err)
		jsonError(w, "Failed to create session", http.StatusInternalServerError)
		return
	}

	logger.Info("admin login successful", "email", loginResp.Email)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"user": map[string]interface{}{
			"email":     loginResp.Email,
			"username":  req.Username,
			"firstName": loginResp.FirstName,
			"lastName":  loginResp.LastName,
			"role":      "Admin",
		},
	})
}

// handleAdminRegisterImpl proxies registration to Spring.
func (s *Server) handleAdminRegisterImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminRegisterURL(), body)
	if err != nil {
		jsonError(w, "Registration service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleAdminVerifyOTPImpl proxies OTP verification to Spring.
func (s *Server) handleAdminVerifyOTPImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminVerifyEmailURL(), body)
	if err != nil {
		jsonError(w, "Verification service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleAdminResendOTPImpl proxies OTP resend to Spring.
func (s *Server) handleAdminResendOTPImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminResendOTPURL(), body)
	if err != nil {
		jsonError(w, "OTP service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleForgotPasswordRequestImpl initiates password reset.
func (s *Server) handleForgotPasswordRequestImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminForgotPasswordURL(), body)
	if err != nil {
		jsonError(w, "Password reset service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleForgotPasswordVerifyOTPImpl verifies password reset OTP.
func (s *Server) handleForgotPasswordVerifyOTPImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	// Use verify-email endpoint for OTP verification (same mechanism)
	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminVerifyEmailURL(), body)
	if err != nil {
		jsonError(w, "Verification service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleForgotPasswordResendImpl resends password reset OTP.
func (s *Server) handleForgotPasswordResendImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminResendOTPURL(), body)
	if err != nil {
		jsonError(w, "OTP service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleForgotPasswordResetImpl submits new password.
func (s *Server) handleForgotPasswordResetImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminPasswordResetURL(), body)
	if err != nil {
		jsonError(w, "Password reset service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared Helpers
// ──────────────────────────────────────────────────────────────────────────────

func jsonError(w http.ResponseWriter, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}

func readBody(r *http.Request) ([]byte, error) {
	return io.ReadAll(r.Body)
}

// Ensure spring package import is used (referenced in LoginResponse type)
var _ spring.LoginResponse
