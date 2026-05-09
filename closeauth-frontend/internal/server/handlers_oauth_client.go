package server

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"

	"closeauth-frontend/internal/middleware"
)

// ──────────────────────────────────────────────────────────────────────────────
// OAuth Client Page Handlers (JSON API for SPA fetch requests)
// ──────────────────────────────────────────────────────────────────────────────

// handleOAuthLoginImpl processes OAuth login form submission from the Vue SPA.
// Returns JSON with redirect_url — Vue does window.location.href to resume the OAuth flow.
func (s *Server) handleOAuthLoginImpl(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "oauth_login")

	// Parse JSON body
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

	// Get OAuth context for JSESSIONID
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		logger.Warn("no OAuth context found for login", "error", err)
		jsonError(w, "OAuth session expired. Please restart the authorization flow.", http.StatusBadRequest)
		return
	}

	// Submit credentials to Spring's login endpoint
	result, err := s.springClient.SubmitLogin(r.Context(), req.Username, req.Password, oauthCtx.SpringSessionID)
	if err != nil {
		logger.Error("spring login proxy failed", "error", err)
		jsonError(w, "Authentication service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Forward Spring cookies to browser
	forwardSpringCookies(w, result.Cookies, s.springConfig.IsProduction())

	// Handle authentication failure
	if result.StatusCode == http.StatusUnauthorized || result.StatusCode == http.StatusForbidden {
		errorMsg := "Invalid username or password"
		// Try to extract error from Spring response
		var springResp struct {
			Error   string `json:"error"`
			Message string `json:"message"`
		}
		if json.Unmarshal(result.Body, &springResp) == nil {
			if springResp.Error != "" {
				errorMsg = springResp.Error
			} else if springResp.Message != "" {
				errorMsg = springResp.Message
			}
		}
		logger.Warn("OAuth login failed", "username", req.Username, "status", result.StatusCode)
		jsonError(w, errorMsg, http.StatusUnauthorized)
		return
	}

	// Authentication successful (200, 302, 303)
	if result.StatusCode != http.StatusOK && result.StatusCode != http.StatusFound && result.StatusCode != http.StatusSeeOther {
		logger.Error("unexpected response from Spring login", "status", result.StatusCode)
		jsonError(w, "Authentication failed", http.StatusInternalServerError)
		return
	}

	logger.Info("OAuth login successful", "username", req.Username, "client_id", oauthCtx.ClientID)

	// Update OAuth context with username and new JSESSIONID
	oauthCtx.Username = req.Username
	for _, c := range result.Cookies {
		if c.Name == "JSESSIONID" {
			oauthCtx.SpringSessionID = c.Value
			logger.Debug("updated JSESSIONID in OAuth context")
			break
		}
	}
	if err := middleware.SaveOAuthContext(w, oauthCtx, s.springConfig.IsProduction()); err != nil {
		logger.Warn("failed to update OAuth context", "error", err)
	}

	// Build redirect URL back to /closeauth/oauth2/authorize to continue the flow
	// Vue will do window.location.href which triggers handleAuthorize (browser navigation)
	redirectURL := "/closeauth/oauth2/authorize?" + url.Values{
		"response_type": {oauthCtx.ResponseType},
		"client_id":     {oauthCtx.ClientID},
		"redirect_uri":  {oauthCtx.RedirectURI},
		"scope":         {oauthCtx.Scope},
		"state":         {oauthCtx.State},
	}.Encode()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"redirect_url": redirectURL,
	})
}

// handleOAuthThemeImpl returns theme data for a client_id from the database.
func (s *Server) handleOAuthThemeImpl(w http.ResponseWriter, r *http.Request) {
	clientID := r.URL.Query().Get("client_id")
	if clientID == "" {
		jsonError(w, "client_id is required", http.StatusBadRequest)
		return
	}

	// Fetch client info from Spring for client name/logo
	var clientName, logoURL string
	if s.springClient != nil {
		clientInfo, err := s.springClient.GetClientInfo(r.Context(), clientID)
		if err == nil {
			clientName = clientInfo.ClientName
			logoURL = clientInfo.LogoURI
		} else {
			s.logger.Warn("failed to fetch client info for theme", "client_id", clientID, "error", err)
		}
	}

	if clientName == "" {
		clientName = clientID
	}

	// Try to get from DB
	if s.themeRepo != nil {
		theme, err := s.themeRepo.FindDefaultTheme(r.Context(), clientID)
		if err == nil {
			// Use logo from Spring if available, else from theme DB
			themeLogoURL := ""
			if theme.LogoURL != nil {
				themeLogoURL = *theme.LogoURL
			}
			if logoURL == "" {
				logoURL = themeLogoURL
			}

			defaultMode := "light"
			if theme.DefaultMode != nil {
				defaultMode = *theme.DefaultMode
			}

			resp := map[string]interface{}{
				"client_id":         clientID,
				"client_name":       clientName,
				"logo_url":          logoURL,
				"default_mode":      defaultMode,
				"allow_mode_toggle": theme.AllowModeToggle,
				"colors": map[string]interface{}{
					"light": theme.GetLightColors(),
					"dark":  theme.GetDarkColors(),
				},
			}

			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(resp)
			return
		}
		s.logger.Debug("no theme found in DB, using defaults", "client_id", clientID)
	}

	// Default theme response
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"client_id":         clientID,
		"client_name":       clientName,
		"logo_url":          logoURL,
		"default_mode":      "light",
		"allow_mode_toggle": true,
		"colors": map[string]interface{}{
			"light": map[string]string{"primary": "#3b82f6", "background": "#ffffff", "button": "#3b82f6", "text": "#1f2937"},
			"dark":  map[string]string{"primary": "#60a5fa", "background": "#1f2937", "button": "#3b82f6", "text": "#f9fafb"},
		},
	})
}

// handleOAuthConsentDataImpl returns consent page data as JSON for the Vue SPA to render.
func (s *Server) handleOAuthConsentDataImpl(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "consent_data")

	// Read OAuth context
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		logger.Warn("no OAuth context for consent data", "error", err)
		jsonError(w, "OAuth session expired", http.StatusBadRequest)
		return
	}

	// Also read from query params (Spring passes them on consent redirect)
	clientID := r.URL.Query().Get("client_id")
	scope := r.URL.Query().Get("scope")
	state := r.URL.Query().Get("state")

	if clientID == "" {
		clientID = oauthCtx.ClientID
	}
	if scope == "" {
		scope = oauthCtx.Scope
	}
	if state == "" {
		state = oauthCtx.State
	}

	// Fetch client info
	clientName := clientID
	var logoURL string
	if s.springClient != nil {
		info, err := s.springClient.GetClientInfo(r.Context(), clientID)
		if err == nil {
			clientName = info.ClientName
			logoURL = info.LogoURI
		}
	}

	// Get CSRF token for the consent form
	csrfToken := ""
	if cookie, err := r.Cookie(middleware.CSRFCookieName); err == nil {
		csrfToken = cookie.Value
	}

	// Build response
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"client_id":   clientID,
		"client_name": clientName,
		"logo_url":    logoURL,
		"username":    oauthCtx.Username,
		"scopes":      splitScopes(scope),
		"state":       state,
		"csrf_token":  csrfToken,
	})
}

// handleOAuthRegisterImpl proxies OAuth registration to the correct Spring OAuth2 registration endpoint.
func (s *Server) handleOAuthRegisterImpl(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(r)
	if err != nil {
		jsonError(w, "Invalid request", http.StatusBadRequest)
		return
	}

	// Extract clientId from the request body or OAuth context
	var payload struct {
		ClientID string `json:"clientId"`
	}
	_ = json.Unmarshal(body, &payload)

	clientID := payload.ClientID
	if clientID == "" {
		if oauthCtx, err := middleware.GetOAuthContext(r); err == nil {
			clientID = oauthCtx.ClientID
		}
	}
	if clientID == "" {
		jsonError(w, "client_id is required for registration", http.StatusBadRequest)
		return
	}

	result, err := s.springClient.ProxyAdminAuth(r.Context(), http.MethodPost, s.springConfig.OAuth2RegisterUserURL(clientID), body, "")
	if err != nil {
		jsonError(w, "Registration service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleOAuthVerifyOTPImpl proxies OTP verification to the OAuth2 verify-email endpoint.
func (s *Server) handleOAuthVerifyOTPImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.OAuth2VerifyEmailURL(), "")
}

// handleOAuthResendOTPImpl proxies OTP resend to the OAuth2 resend-email-otp endpoint.
func (s *Server) handleOAuthResendOTPImpl(w http.ResponseWriter, r *http.Request) {
	s.proxyToSpring(w, r, http.MethodPost, s.springConfig.OAuth2ResendEmailOtpURL(), "")
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

func splitScopes(scope string) []string {
	if scope == "" {
		return []string{}
	}
	parts := strings.Split(scope, " ")
	var result []string
	for _, p := range parts {
		trimmed := strings.TrimSpace(p)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}
