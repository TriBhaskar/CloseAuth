package server

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
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

	logger.Info("attempting admin login", "url", s.springConfig.AdminLoginURL())
	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.AdminLoginURL(), jsonBody, "")
	if err != nil {
		logger.Error("admin login proxy failed", "error", err)
		jsonError(w, "Authentication service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Handle non-200 responses — try to extract error message from body
	if result.StatusCode != http.StatusOK {
		errorMsg := "Authentication failed"
		var errResp spring.ApiErrorResponse
		if json.Unmarshal(result.Body, &errResp) == nil {
			if errResp.Error != "" {
				errorMsg = errResp.Error
			} else if errResp.Message != "" {
				errorMsg = errResp.Message
			}
		}
		status := result.StatusCode
		if status == http.StatusForbidden {
			status = http.StatusUnauthorized
		}
		jsonError(w, errorMsg, status)
		return
	}

	// Parse Spring response
	var loginResp spring.LoginResponse
	if err := json.Unmarshal(result.Body, &loginResp); err != nil {
		logger.Error("failed to parse login response", "error", err)
		jsonError(w, "Authentication failed", http.StatusInternalServerError)
		return
	}

	// Login successful — set encrypted session cookie
	session := &middleware.Session{
		UserID:      fmt.Sprintf("%d", loginResp.UserID),
		Email:       loginResp.Email,
		Username:    req.Username,
		Role:        "Admin",
		AccessToken: loginResp.AccessToken,
		ExpiresAt:   time.Now().Add(24 * time.Hour).Unix(),
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
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.AdminRegisterURL(), "")
}

// handleAdminVerifyOTPImpl proxies OTP verification to Spring.
func (s *Server) handleAdminVerifyOTPImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.AdminVerifyEmailURL(), "")
}

// handleAdminResendOTPImpl proxies OTP resend to Spring.
func (s *Server) handleAdminResendOTPImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.AdminResendOTPURL(), "")
}

// handleForgotPasswordRequestImpl initiates password reset.
func (s *Server) handleForgotPasswordRequestImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.AdminForgotPasswordURL(), "")
}

// handleValidateResetTokenImpl validates a password reset token.
func (s *Server) handleValidateResetTokenImpl(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		jsonError(w, "Token is required", http.StatusBadRequest)
		return
	}

	targetURL := s.springConfig.AdminValidateResetTokenURL() + "?token=" + url.QueryEscape(token)
	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodGet, targetURL, nil, "")
	if err != nil {
		s.logger.Error("proxy to Spring failed", "url", targetURL, "error", err)
		jsonError(w, "Service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Gaurd against non-JSON responses (e.g Spring redirect to login page)
	if result.StatusCode >= 300 && result.StatusCode < 400 {
		s.logger.Error("unexpected redirect from Spring validate-token", "status", result.StatusCode, "location", string(result.Body))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"valid": false,
			"error": "Passed reset service temporarily unavailable",
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleForgotPasswordResetImpl submits new password.
func (s *Server) handleForgotPasswordResetImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.AdminPasswordResetURL(), "")
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared Helpers
// ──────────────────────────────────────────────────────────────────────────────

// proxyToSpring reads the request body, proxies it to the given Spring URL via
// ProxyAdminAuth (with BFF bearer token + optional X-User-Token), and writes
// the Spring response back to the client.
func (s *Server) proxyToSpring(w http.ResponseWriter, r *http.Request, method, targetURL, userToken string) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), method, targetURL, body, userToken)
	if err != nil {
		s.logger.Error("proxy to Spring failed", "url", targetURL, "error", err)
		jsonError(w, "Service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// getUserToken extracts the user's access token from the session cookie.
// Returns "" if no session is present (public endpoints).
func getUserToken(r *http.Request) string {
	session, err := middleware.GetSession(r)
	if err != nil {
		return ""
	}
	return session.AccessToken
}

func jsonError(w http.ResponseWriter, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}

func readBody(r *http.Request) ([]byte, error) {
	return io.ReadAll(r.Body)
}
