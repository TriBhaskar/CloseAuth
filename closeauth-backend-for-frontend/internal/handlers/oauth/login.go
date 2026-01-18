package oauth

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/handlers/response"
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
	logger              *slog.Logger
}

// NewOAuthClientAuthHandler creates a new OAuth client auth handler instance
func NewOAuthClientAuthHandler(themeRepo *repository.ThemeRepository) *OAuthClientAuthHandler {
	endpoints, err := config.LoadEndpointsConfig()
	if err != nil {
		slog.Warn("failed to load endpoints config", "error", err)
	}

	// Initialize OAuth client config and token manager
	oauthConfig := sasconfig.LoadOAuthClientConfig()
	tokenManager := service.NewTokenManager(oauthConfig)
	authenticatedClient := service.NewAuthenticatedClient(tokenManager, endpoints)

	return &OAuthClientAuthHandler{
		themeRepo:           themeRepo,
		endpoints:           endpoints,
		authenticatedClient: authenticatedClient,
		logger:              slog.Default().With("handler", "oauth_client_auth"),
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

	if clientID != "" && h.authenticatedClient != nil {
		clientInfo, err := h.authenticatedClient.GetClientInfo(r.Context(), clientID)
		if err != nil {
			h.logger.Warn("failed to fetch client info, using defaults", "client_id", clientID, "error", err)
		} else {
			// Use client name from server
			if clientInfo.ClientName != "" {
				clientName = clientInfo.ClientName
			}
			// Use logo URI from server if available, otherwise keep theme logo
			if clientInfo.LogoURI != "" {
				themeData.LogoURL = &clientInfo.LogoURI
			}
		}
	}

	if clientName == "" {
		clientName = "Application"
	}

	// Build login data
	loginData := templates.OAuthLoginData{
		CSRFToken:   csrfToken,
		Theme:       convertThemeToThemeData(themeData),
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
	// Parse and validate form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	username := form.GetRequired("username", "Username or email")
	password := form.GetRequired("password", "Password")
	clientID := form.Get("client_id")
	continueURL := form.Get("continue")

	// Check for validation errors
	if form.HasErrors() {
		response.RenderError(w, r, form.FirstError(), http.StatusBadRequest)
		return
	}

	// Get OAuth context to retrieve the preserved JSESSIONID from the initial authorize request
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		h.logger.Warn("could not retrieve OAuth context", "error", err)
	}

	// Authenticate against Spring Authorization Server using x-www-form-urlencoded
	formData := url.Values{}
	formData.Set("username", username)
	formData.Set("password", password)

	authURL := h.endpoints.GetLoginURL()

	// Debug logging: Print request details
	h.logger.Debug("OAuth login request debug")
	h.logger.Debug("login request URL", "url", authURL)
	h.logger.Debug("login request body", "body", formData.Encode())

	// Create a direct HTTP request to Spring's login endpoint
	// This allows us to include the preserved JSESSIONID for session continuity
	httpClient := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // Don't follow redirects, we handle them ourselves
		},
	}

	req, err := http.NewRequest("POST", authURL, strings.NewReader(formData.Encode()))
	if err != nil {
		h.logger.Error("failed to create login request", "error", err)
		response.RenderError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
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
		h.logger.Debug("attached preserved JSESSIONID to login request")
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		h.logger.Error("failed to call auth service for OAuth client login", "error", err)
		response.RenderError(w, r, "Authentication service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		h.logger.Error("failed to read OAuth auth response", "error", err)
		response.RenderError(w, r, "Failed to process authentication response", http.StatusInternalServerError)
		return
	}

	h.logger.Debug("OAuth auth server response", "status", resp.StatusCode, "body", string(body))

	// Check if authentication was successful
	switch resp.StatusCode {
	case http.StatusOK, http.StatusFound, http.StatusSeeOther, http.StatusMovedPermanently:
		// Success - authentication passed
		h.logger.Info("OAuth client login successful", "username", username, "client_id", clientID)

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
			h.logger.Debug("forwarded cookie from Spring", "name", cookie.Name)
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

		h.logger.Warn("OAuth client login failed", "username", username, "client_id", clientID, "error", errorMsg, "status", resp.StatusCode)
		response.RenderError(w, r, errorMsg, http.StatusUnauthorized)
		return

	default:
		// Unexpected response
		h.logger.Error("unexpected response from OAuth auth service", "status", resp.StatusCode, "body", string(body))
		response.RenderError(w, r, "Authentication failed. Please try again.", http.StatusInternalServerError)
		return
	}

	h.logger.Info("OAuth client login successful", "username", username, "client_id", clientID)

	// Save username and new Spring JSESSIONID to OAuth context for consent page
	if oauthCtx != nil {
		oauthCtx.Username = username
		// Update SpringSessionID with the new session from login response
		for _, cookie := range resp.Cookies() {
			if cookie.Name == "JSESSIONID" {
				oauthCtx.SpringSessionID = cookie.Value
				h.logger.Debug("saved new Spring JSESSIONID to OAuth context for consent")
				break
			}
		}
		if err := middleware.SaveOAuthContext(w, oauthCtx); err != nil {
			h.logger.Warn("failed to save OAuth context", "error", err)
		}
	}

	// After successful login, redirect back to OAuth flow or continue URL
	h.logger.Debug("continue URL", "url", continueURL, "oauth_ctx_nil", oauthCtx == nil)

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
		h.logger.Debug("redirecting to OAuth authorize endpoint", "url", redirectURL)
	}

	if redirectURL == "" {
		h.logger.Warn("no redirect URL found, falling back to home")
		redirectURL = "/" // Fallback to home
	}

	h.logger.Debug("final redirect URL", "url", redirectURL)

	// Note: We don't clear OAuth context here anymore - consent page needs it
	// The context will be cleared after consent is granted/denied

	// Handle HTMX vs standard redirect
	if middleware.IsHTMXRequest(r) {
		middleware.HTMXRedirect(w, redirectURL)
	} else {
		http.Redirect(w, r, redirectURL, http.StatusSeeOther)
	}
}
