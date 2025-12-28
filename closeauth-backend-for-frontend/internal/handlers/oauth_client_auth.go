package handlers

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"

	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/database/models"
	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/middleware"
	sasconfig "closeauth-backend-for-frontend/internal/sas/config"
	"closeauth-backend-for-frontend/internal/sas/service"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// OAuthClientAuthHandler handles authentication for OAuth2 client redirects
// This handler provides client-specific themed login/registration pages
type OAuthClientAuthHandler struct {
	themeRepo           *repository.ThemeRepository
	endpoints           *config.EndpointsConfig
	authenticatedClient *service.AuthenticatedClient
}

// NewOAuthClientAuthHandler creates a new OAuth client auth handler instance
func NewOAuthClientAuthHandler(themeRepo *repository.ThemeRepository) *OAuthClientAuthHandler {
	endpoints, err := config.LoadEndpointsConfig()
	if err != nil {
		log.Printf("Warning: Failed to load endpoints config: %v", err)
	}

	// Initialize OAuth client config and token manager
	oauthConfig := sasconfig.LoadOAuthClientConfig()
	tokenManager := service.NewTokenManager(oauthConfig)
	authenticatedClient := service.NewAuthenticatedClient(tokenManager, endpoints)

	return &OAuthClientAuthHandler{
		themeRepo:           themeRepo,
		endpoints:           endpoints,
		authenticatedClient: authenticatedClient,
	}
}

// HandleOAuthLoginGet renders the client-themed login page
func (h *OAuthClientAuthHandler) HandleOAuthLoginGet(w http.ResponseWriter, r *http.Request) {
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

	// Fetch client info from Spring Authorization Server
	clientName := clientID
	logoURL := themeData.LogoURL
	
	if clientID != "" && h.authenticatedClient != nil {
		clientInfo, err := h.authenticatedClient.GetClientInfo(r.Context(), clientID)
		if err != nil {
			log.Printf("Failed to fetch client info for %s: %v (using defaults)", clientID, err)
		} else {
			// Use client name from server
			if clientInfo.ClientName != "" {
				clientName = clientInfo.ClientName
			}
			// Use logo URI from server if available, otherwise keep theme logo
			if clientInfo.LogoURI != "" {
				logoURL = clientInfo.LogoURI
				themeData.LogoURL = logoURL
			}
		}
	}
	
	if clientName == "" {
		clientName = "Application"
	}

	// Build login data
	loginData := templates.OAuthLoginData{
		CSRFToken:   csrfToken,
		Theme:       themeData,
		ClientName:  clientName,
		ErrorMsg:    "",
		ContinueURL: continueURL,
	}

	// Render the OAuth login template
	component := templates.OAuthLogin(loginData)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthLoginPost processes the client-themed login form submission
func (h *OAuthClientAuthHandler) HandleOAuthLoginPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	if err := r.ParseForm(); err != nil {
		h.handleOAuthLoginError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	username := r.FormValue("username")
	password := r.FormValue("password")
	clientID := r.FormValue("client_id")
	continueURL := r.FormValue("continue")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("username", username, "Username or email is required")
	validator.Required("password", password, "Password is required")

	if !validator.IsValid() {
		h.handleOAuthLoginError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	// Get OAuth context to retrieve the preserved JSESSIONID from the initial authorize request
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		log.Printf("WARNING: Could not retrieve OAuth context: %v", err)
	}

	// Authenticate against Spring Authorization Server using x-www-form-urlencoded
	formData := url.Values{}
	formData.Set("username", username)
	formData.Set("password", password)

	authURL := h.endpoints.GetLoginURL()

	// Debug logging: Print request details
	log.Printf("=== OAuth Login Request Debug ===")
	log.Printf("URL: %s", authURL)
	log.Printf("Body (form-urlencoded): %s", formData.Encode())

	// Create a direct HTTP request to Spring's login endpoint
	// This allows us to include the preserved JSESSIONID for session continuity
	httpClient := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // Don't follow redirects, we handle them ourselves
		},
	}

	req, err := http.NewRequest("POST", authURL, strings.NewReader(formData.Encode()))
	if err != nil {
		log.Printf("Error creating login request: %v", err)
		h.handleOAuthLoginError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	// Include the preserved JSESSIONID from OAuth context for session continuity
	// This ensures Spring recognizes the same session that has the saved authorize request
	if oauthCtx != nil && oauthCtx.SpringSessionID != "" {
		req.AddCookie(&http.Cookie{
			Name:  "JSESSIONID",
			Value: oauthCtx.SpringSessionID,
		})
		log.Printf("INFO: Attached preserved JSESSIONID to login request for session continuity")
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		log.Printf("Error calling auth service for OAuth client login: %v", err)
		h.handleOAuthLoginError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Debug logging: Print response request details (shows what was actually sent)
	if resp.Request != nil {
		log.Printf("=== Actual Request Headers ===")
		for key, values := range resp.Request.Header {
			for _, value := range values {
				log.Printf("Header: %s: %s", key, value)
			}
		}
	}
	log.Printf("=== End OAuth Login Request Debug ===")

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Printf("Error reading OAuth auth response: %v", err)
		h.handleOAuthLoginError(w, r, "Failed to process authentication response", http.StatusInternalServerError)
		return
	}

	log.Printf("OAuth auth server response: Status=%d, Body=%s", resp.StatusCode, string(body))

	// Check if authentication was successful
	switch resp.StatusCode {
	case http.StatusOK, http.StatusFound, http.StatusSeeOther, http.StatusMovedPermanently:
		// Success - authentication passed
		log.Printf("OAuth client login successful for user: %s, client: %s", username, clientID)

		// Forward session cookies from Spring to browser with proper security settings
		for _, cookie := range resp.Cookies() {
			newCookie := &http.Cookie{
				Name:     cookie.Name,
				Value:    cookie.Value,
				Path:     cookie.Path,
				MaxAge:   cookie.MaxAge,
				HttpOnly: true,
				Secure:   isProduction(),
				SameSite: http.SameSiteLaxMode,
			}
			if newCookie.Path == "" {
				newCookie.Path = "/"
			}
			http.SetCookie(w, newCookie)
			log.Printf("INFO: Forwarded cookie from Spring: %s", cookie.Name)
		}

	case http.StatusUnauthorized, http.StatusForbidden:
		// Authentication failed
		errorMsg := "Invalid username or password"

		// Try to parse error from response
		var loginResp struct {
			Error   string `json:"error,omitempty"`
			Message string `json:"message,omitempty"`
		}
		if len(body) > 0 && json.Unmarshal(body, &loginResp) == nil {
			if loginResp.Error != "" {
				errorMsg = loginResp.Error
			} else if loginResp.Message != "" {
				errorMsg = loginResp.Message
			}
		}

		log.Printf("OAuth client login failed for %s (client: %s): %s (Status: %d)", username, clientID, errorMsg, resp.StatusCode)
		h.handleOAuthLoginError(w, r, errorMsg, http.StatusUnauthorized)
		return

	default:
		// Unexpected response
		log.Printf("Unexpected response from OAuth auth service: Status=%d, Body=%s", resp.StatusCode, string(body))
		h.handleOAuthLoginError(w, r, "Authentication failed. Please try again.", http.StatusInternalServerError)
		return
	}

	log.Printf("OAuth client login successful for user: %s, client: %s", username, clientID)

	// Save username and new Spring JSESSIONID to OAuth context for consent page
	if oauthCtx != nil {
		oauthCtx.Username = username
		// Update SpringSessionID with the new session from login response
		for _, cookie := range resp.Cookies() {
			if cookie.Name == "JSESSIONID" {
				oauthCtx.SpringSessionID = cookie.Value
				log.Printf("INFO: Saved new Spring JSESSIONID to OAuth context for consent")
				break
			}
		}
		if err := middleware.SaveOAuthContext(w, oauthCtx); err != nil {
			log.Printf("WARNING: Failed to save OAuth context: %v", err)
		}
	}

	// After successful login, redirect back to OAuth flow or continue URL
	log.Printf("DEBUG: continueURL='%s', oauthCtx is nil=%v", continueURL, oauthCtx == nil)
	
	var redirectURL string
	
	// Check if continueURL is a valid redirect URL (not just "true" from ?continue=true)
	// If it's "true" or empty, we should use the OAuth context to build the authorize URL
	if continueURL != "" && continueURL != "true" && strings.HasPrefix(continueURL, "/") {
		redirectURL = continueURL
	} else if oauthCtx != nil {
		// Redirect back to authorization endpoint to continue OAuth flow
		// Use url.QueryEscape to properly encode parameters
		redirectURL = "/closeauth/oauth2/authorize?response_type=" + url.QueryEscape(oauthCtx.ResponseType) +
			"&client_id=" + url.QueryEscape(oauthCtx.ClientID) +
			"&redirect_uri=" + url.QueryEscape(oauthCtx.RedirectURI) +
			"&scope=" + url.QueryEscape(oauthCtx.Scope) +
			"&state=" + url.QueryEscape(oauthCtx.State)
		log.Printf("INFO: Redirecting to OAuth authorize endpoint: %s", redirectURL)
	}

	if redirectURL == "" {
		log.Printf("WARNING: No redirect URL found, falling back to home")
		redirectURL = "/" // Fallback to home
	}

	log.Printf("INFO: Final redirect URL: %s", redirectURL)

	// Note: We don't clear OAuth context here anymore - consent page needs it
	// The context will be cleared after consent is granted/denied

	// Handle HTMX vs standard redirect
	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, redirectURL)
	} else {
		http.Redirect(w, r, redirectURL, http.StatusSeeOther)
	}
}

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
		Theme:       themeData,
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
	// Parse form data
	if err := r.ParseForm(); err != nil {
		h.handleOAuthRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	firstName := r.FormValue("firstName")
	lastName := r.FormValue("lastName")
	email := r.FormValue("email")
	username := r.FormValue("username")
	password := r.FormValue("password")
	confirmPassword := r.FormValue("confirmPassword")
	clientID := r.FormValue("client_id")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("firstName", firstName, "First name is required")
	validator.Required("lastName", lastName, "Last name is required")
	validator.Required("email", email, "Email is required")
	validator.Email("email", email, "Please enter a valid email address")
	validator.Required("password", password, "Password is required")
	validator.MinLength("password", password, 8, "Password must be at least 8 characters")
	validator.Required("confirmPassword", confirmPassword, "Please confirm your password")

	if password != confirmPassword {
		validator.AddError("confirmPassword", "Passwords do not match")
	}

	if !validator.IsValid() {
		h.handleOAuthRegisterError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	log.Printf("OAuth client registration request for email: %s, client: %s, username: %s", email, clientID, username)

	// TODO: Call your external service to register user and send OTP
	// Example:
	// err := h.authService.RegisterUser(firstName, lastName, email, username, password)
	// if err != nil {
	//     h.handleOAuthRegisterError(w, r, "Registration failed", http.StatusInternalServerError)
	//     return
	// }

	// Get CSRF token and theme for OTP dialog
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
	themeData := h.getClientTheme(r, clientID)

	// Return OTP verification dialog
	component := templates.OAuthRegisterOTPDialog(csrfToken, email, clientID, themeData)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthVerifyRegistrationOTP verifies the registration OTP
func (h *OAuthClientAuthHandler) HandleOAuthVerifyRegistrationOTP(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	if err := r.ParseForm(); err != nil {
		h.handleOAuthRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	email := r.FormValue("email")
	otp := r.FormValue("otp")
	clientID := r.FormValue("client_id")

	// Validate inputs
	validator := middleware.NewFormValidator()
	validator.Required("email", email, "Email is required")
	validator.Required("otp", otp, "Verification code is required")
	validator.MinLength("otp", otp, 6, "Verification code must be 6 digits")

	if !validator.IsValid() {
		h.handleOAuthRegisterError(w, r, validator.Errors[0].Message, http.StatusBadRequest)
		return
	}

	log.Printf("OAuth registration OTP verification for email: %s, client: %s", email, clientID)

	// TODO: Verify OTP with your auth service
	// Example:
	// err := h.authService.VerifyRegistrationOTP(email, otp)
	// if err != nil {
	//     h.handleOAuthRegisterError(w, r, "Invalid or expired verification code", http.StatusUnauthorized)
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
		h.handleOAuthRegisterError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	email := r.FormValue("email")
	clientID := r.FormValue("client_id")

	log.Printf("Resending OAuth registration OTP for email: %s, client: %s", email, clientID)

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

// HandleOAuthConsentGet renders the OAuth consent page
// Spring Authorization Server redirects here: /oauth/consent?scope=...&client_id=...&state=...
func (h *OAuthClientAuthHandler) HandleOAuthConsentGet(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters from Spring Authorization Server
	clientID := r.URL.Query().Get("client_id")
	scopeParam := r.URL.Query().Get("scope")
	state := r.URL.Query().Get("state")

	log.Printf("OAuth consent page requested: client_id=%s, scope=%s, state=%s", clientID, scopeParam, state)

	// Get OAuth context to retrieve username and redirect_uri
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		log.Printf("WARNING: Could not retrieve OAuth context for consent: %v", err)
	}

	username := ""
	redirectURI := ""
	if oauthCtx != nil {
		username = oauthCtx.Username
		redirectURI = oauthCtx.RedirectURI
		// If clientID not in query, use from context
		if clientID == "" {
			clientID = oauthCtx.ClientID
		}
	}

	// Get CSRF token
	csrfToken := middleware.GetCSRFTokenFromContext(r.Context())

	// Fetch client theme from database
	themeData := h.getClientTheme(r, clientID)

	// Fetch client info from Spring Authorization Server
	clientName := clientID
	logoURL := themeData.LogoURL

	if clientID != "" && h.authenticatedClient != nil {
		clientInfo, err := h.authenticatedClient.GetClientInfo(r.Context(), clientID)
		if err != nil {
			log.Printf("Failed to fetch client info for consent page %s: %v", clientID, err)
		} else {
			if clientInfo.ClientName != "" {
				clientName = clientInfo.ClientName
			}
			if clientInfo.LogoURI != "" {
				logoURL = clientInfo.LogoURI
			}
		}
	}

	if clientName == "" {
		clientName = "Application"
	}

	// Parse scopes from query parameter (space-separated)
	scopes := strings.Fields(scopeParam)
	scopeDisplays := templates.MapScopesToDisplay(scopes)

	// Build consent data
	consentData := templates.OAuthConsentData{
		CSRFToken:   csrfToken,
		Theme:       themeData,
		ClientID:    clientID,
		ClientName:  clientName,
		LogoURL:     logoURL,
		Username:    username,
		Email:       "", // Email not available yet
		Scopes:      scopeDisplays,
		State:       state,
		RedirectURI: redirectURI,
		ErrorMsg:    "",
	}

	// Render the consent template
	component := templates.OAuthConsent(consentData)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthConsentPost processes the consent form submission
func (h *OAuthClientAuthHandler) HandleOAuthConsentPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	if err := r.ParseForm(); err != nil {
		log.Printf("Error parsing consent form: %v", err)
		http.Error(w, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	action := r.FormValue("action")
	clientID := r.FormValue("client_id")
	state := r.FormValue("state")
	redirectURI := r.FormValue("redirect_uri")
	scopes := r.Form["scope"] // Multiple scope values

	log.Printf("OAuth consent submitted: action=%s, client_id=%s, state=%s, scopes=%v", action, clientID, state, scopes)

	// Get OAuth context for additional parameters needed for authorize request
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		log.Printf("WARNING: Could not retrieve OAuth context for consent: %v", err)
	}

	// Fill in missing values from OAuth context
	if oauthCtx != nil {
		if redirectURI == "" {
			redirectURI = oauthCtx.RedirectURI
		}
		if state == "" {
			state = oauthCtx.State
		}
		if clientID == "" {
			clientID = oauthCtx.ClientID
		}
	}

	// Handle deny action
	if action == "deny" {
		log.Printf("User denied consent for client: %s", clientID)

		// Clear OAuth context
		middleware.ClearOAuthContext(w)

		// Redirect to client with access_denied error
		if redirectURI != "" {
			errorURL := redirectURI
			if strings.Contains(redirectURI, "?") {
				errorURL += "&"
			} else {
				errorURL += "?"
			}
			errorURL += "error=access_denied&error_description=User+denied+consent"
			if state != "" {
				errorURL += "&state=" + url.QueryEscape(state)
			}
			http.Redirect(w, r, errorURL, http.StatusFound)
		} else {
			http.Redirect(w, r, "/", http.StatusFound)
		}
		return
	}

	// Handle approve action - POST to Spring Authorization Server's authorize endpoint
	if action == "approve" {
		log.Printf("User approved consent for client: %s with scopes: %v", clientID, scopes)

		// Build the authorize URL with consent parameters
		// Spring expects the consent to be submitted to the authorize endpoint
		authorizeURL := h.endpoints.GetAuthorizeURL()

		// Build form data for Spring Authorization Server consent submission
		// IMPORTANT: Only send client_id, state, and scope for consent confirmation
		// Including response_type or redirect_uri makes Spring treat this as a NEW authorization request
		// instead of a consent confirmation, causing the consent loop issue
		formData := url.Values{}
		formData.Set("client_id", clientID)
		formData.Set("state", state)
		// Add all consented scopes (space-separated as per OAuth2 spec)
		scopeStr := strings.Join(scopes, " ")
		formData.Set("scope", scopeStr)

		log.Printf("Submitting consent to Spring: URL=%s, Data=%s", authorizeURL, formData.Encode())

		// Create request to Spring Authorization Server
		req, err := http.NewRequest("POST", authorizeURL, strings.NewReader(formData.Encode()))
		if err != nil {
			log.Printf("Error creating consent request: %v", err)
			http.Error(w, "Failed to process consent", http.StatusInternalServerError)
			return
		}

		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

		// Use the preserved JSESSIONID from OAuth context for session continuity
		// This is critical because the browser won't send Spring's JSESSIONID to BFF (different domain)
		if oauthCtx != nil && oauthCtx.SpringSessionID != "" {
			req.AddCookie(&http.Cookie{
				Name:  "JSESSIONID",
				Value: oauthCtx.SpringSessionID,
			})
			log.Printf("INFO: Using preserved Spring JSESSIONID for consent submission")
		} else {
			log.Printf("WARNING: No Spring JSESSIONID found in OAuth context, consent may fail")
			// Fallback: try forwarding cookies from browser (unlikely to work)
			for _, cookie := range r.Cookies() {
				if cookie.Name == "JSESSIONID" {
					req.AddCookie(cookie)
				}
			}
		}

		// Create HTTP client that doesn't follow redirects
		httpClient := &http.Client{
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}

		resp, err := httpClient.Do(req)
		if err != nil {
			log.Printf("Error submitting consent to Spring: %v", err)
			http.Error(w, "Failed to submit consent", http.StatusInternalServerError)
			return
		}
		defer resp.Body.Close()

		log.Printf("Spring consent response: Status=%d", resp.StatusCode)

		// Clear OAuth context after successful consent
		middleware.ClearOAuthContext(w)

		// Forward any cookies from Spring
		for _, cookie := range resp.Cookies() {
			http.SetCookie(w, cookie)
		}

		// Handle Spring's response
		if resp.StatusCode == http.StatusFound || resp.StatusCode == http.StatusSeeOther {
			// Spring is redirecting - follow to client's redirect_uri
			location := resp.Header.Get("Location")
			if location != "" {
				log.Printf("Redirecting to: %s", location)
				http.Redirect(w, r, location, http.StatusFound)
				return
			}
		}

		// If Spring returned 200, the authorization was successful
		// Redirect back to client's redirect_uri with the code
		if resp.StatusCode == http.StatusOK {
			log.Printf("Consent approved, redirecting to client")
			if redirectURI != "" {
				http.Redirect(w, r, redirectURI, http.StatusFound)
			} else {
				http.Redirect(w, r, "/", http.StatusFound)
			}
			return
		}

		// Handle errors
		body, _ := io.ReadAll(resp.Body)
		log.Printf("Unexpected response from Spring: Status=%d, Body=%s", resp.StatusCode, string(body))
		http.Error(w, "Consent processing failed", http.StatusInternalServerError)
		return
	}

	// Unknown action
	http.Error(w, "Invalid action", http.StatusBadRequest)
}

// getClientTheme fetches the client's theme from the database
func (h *OAuthClientAuthHandler) getClientTheme(r *http.Request, clientID string) templates.ThemeData {
	// Return default theme if no client ID or theme repo
	if clientID == "" || h.themeRepo == nil {
		return templates.DefaultThemeData()
	}

	// Fetch theme from database
	theme, err := h.themeRepo.FindDefaultTheme(r.Context(), clientID)
	if err != nil {
		log.Printf("Failed to fetch theme for client %s: %v", clientID, err)
		return templates.DefaultThemeData()
	}

	// Convert database model to template data
	return h.convertThemeToTemplateData(theme, clientID)
}

// convertThemeToTemplateData converts a database theme model to template data
func (h *OAuthClientAuthHandler) convertThemeToTemplateData(theme *models.ClientTheme, clientID string) templates.ThemeData {
	if theme == nil {
		defaultTheme := templates.DefaultThemeData()
		defaultTheme.ClientID = clientID
		return defaultTheme
	}

	logoURL := ""
	if theme.LogoURL != nil {
		logoURL = *theme.LogoURL
	}

	return templates.ThemeData{
		ClientID:        clientID,
		ThemeName:       theme.ThemeName,
		LogoURL:         logoURL,
		DefaultMode:     theme.GetDefaultMode(),
		AllowModeToggle: theme.AllowModeToggle,
		LightColors:     theme.GetLightColors(),
		DarkColors:      theme.GetDarkColors(),
	}
}

// getClientName returns a display name for the client
// TODO: Fetch from database or configuration
func (h *OAuthClientAuthHandler) getClientName(clientID string) string {
	if clientID == "" {
		return "Application"
	}
	// TODO: Look up client name from database
	// For now, return a formatted version of the client ID
	return clientID
}

// handleOAuthLoginError handles login errors with HTMX/standard dual response
func (h *OAuthClientAuthHandler) handleOAuthLoginError(w http.ResponseWriter, r *http.Request, message string, code int) {
	if middleware.IsHTMXRequest(r) {
		component := templates.OAuthErrorMessage(message)
		w.WriteHeader(code)
		templ.Handler(component).ServeHTTP(w, r)
	} else {
		http.Error(w, message, code)
	}
}

// handleOAuthRegisterError handles registration errors with HTMX/standard dual response
func (h *OAuthClientAuthHandler) handleOAuthRegisterError(w http.ResponseWriter, r *http.Request, message string, code int) {
	if middleware.IsHTMXRequest(r) {
		component := templates.OAuthErrorMessage(message)
		w.WriteHeader(code)
		templ.Handler(component).ServeHTTP(w, r)
	} else {
		http.Error(w, message, code)
	}
}
