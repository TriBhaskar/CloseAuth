package admin

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
			finalRedirect = constants.RouteAdminDashboard
		}

		// Redirect user
		if middleware.IsHTMXRequest(r) {
			middleware.HTMXRedirect(w, finalRedirect)
		} else {
			http.Redirect(w, r, finalRedirect, http.StatusSeeOther)
		}

	case http.StatusUnauthorized, http.StatusForbidden:
		// Authentication failed
		h.logger.Warn("login_failed", "email", email, "status_code", resp.StatusCode)
		response.RenderError(w, r, "Invalid email or password", http.StatusUnauthorized)

	default:
		// Handle other unexpected errors
		h.logger.Error("unhandled_auth_error", "email", email, "status_code", resp.StatusCode, "body_length", len(body))
		response.RenderError(w, r, "An unexpected error occurred during login", resp.StatusCode)
	}
}

// HandleLogoutPost handles user logout
func (h *AuthHandler) HandleLogoutPost(w http.ResponseWriter, r *http.Request) {
	// Clear the session cookie
	middleware.ClearSession(w)

	h.logger.Info("user_logged_out")

	// Redirect to login page
	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, constants.RouteAdminLogin)
	} else {
		http.Redirect(w, r, constants.RouteAdminLogin, http.StatusSeeOther)
	}
}

// forwardSessionCookies inspects the response for Set-Cookie headers and forwards them
func (h *AuthHandler) forwardSessionCookies(w http.ResponseWriter, resp *http.Response) {
	for _, cookie := range resp.Cookies() {
		// Specifically forward the JSESSIONID cookie from the Spring backend
		if cookie.Name == "JSESSIONID" {
			http.SetCookie(w, cookie)
			h.logger.Debug("forwarded_cookie", "name", cookie.Name, "value", cookie.Value)
		}
	}
}
