package handlers

import (
	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/middleware"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
)

// OAuthProxyHandler handles transparent proxying of OAuth2 endpoints
// to the Spring Authorization Server while managing the authentication flow.
//
// This handler implements the Backend-for-Frontend (BFF) pattern for OAuth2,
// intercepting authorization requests to provide a custom login experience
// while maintaining session security with the authorization server.
type OAuthProxyHandler struct {
	endpoints  *config.EndpointsConfig
	bffBaseURL string // Base URL of this BFF server (e.g., http://localhost:8088)
}

// NewOAuthProxyHandler creates a new OAuth proxy handler instance.
// Configuration is read from environment variables with sensible defaults.
func NewOAuthProxyHandler() *OAuthProxyHandler {
	endpoints, err := config.LoadEndpointsConfig()
	if err != nil {
		log.Printf("Warning: Failed to load endpoints config: %v", err)
	}
	
	return &OAuthProxyHandler{
		endpoints:  endpoints,
		bffBaseURL: config.GetEnvOrDefault("BFF_BASE_URL", "http://localhost:8088"),
	}
}

// HandleAuthorize proxies OAuth2 authorization requests to Spring Authorization Server.
//
// Flow:
//  1. External client requests authorization code
//  2. BFF proxies request to Spring Authorization Server
//  3. If user not authenticated:
//     a. Spring redirects to login page
//     b. BFF intercepts redirect and saves OAuth context in encrypted cookie
//     c. BFF redirects user to custom login page
//  4. After successful login:
//     a. User is redirected back with JSESSIONID
//     b. BFF proxies request again with session cookie
//     c. Spring generates authorization code
//     d. Spring redirects to client's redirect_uri with code
func (h *OAuthProxyHandler) HandleAuthorize(w http.ResponseWriter, r *http.Request) {
	// Extract and validate OAuth parameters
	params := h.extractOAuthParams(r)
	if err := h.validateOAuthParams(params); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Proxy request to Spring Authorization Server
	resp, err := h.proxyToSpring(r, "/closeauth/oauth2/authorize")
	if err != nil {
		log.Printf("ERROR: Failed to proxy authorize request: %v", err)
		http.Error(w, "Authorization service unavailable", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Handle redirect responses (login redirect or authorization code redirect)
	if h.isRedirect(resp.StatusCode) {
		h.handleAuthorizeRedirect(w, r, resp, params)
		return
	}

	// Forward non-redirect responses directly to client
	h.forwardResponse(w, resp)
}

// HandleToken proxies OAuth2 token exchange requests to Spring Authorization Server.
// This endpoint is called by clients to exchange authorization codes for access tokens.
func (h *OAuthProxyHandler) HandleToken(w http.ResponseWriter, r *http.Request) {
	// Only allow POST method for token requests
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Proxy request to Spring Authorization Server
	resp, err := h.proxyToSpring(r, "/closeauth/oauth2/token")
	if err != nil {
		log.Printf("ERROR: Failed to proxy token request: %v", err)
		http.Error(w, "Authorization service unavailable", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	// Forward response directly to client
	h.forwardResponse(w, resp)
}

// extractOAuthParams extracts OAuth2 parameters from the request query string
func (h *OAuthProxyHandler) extractOAuthParams(r *http.Request) map[string]string {
	query := r.URL.Query()
	return map[string]string{
		"response_type": query.Get("response_type"),
		"client_id":     query.Get("client_id"),
		"redirect_uri":  query.Get("redirect_uri"),
		"scope":         query.Get("scope"),
		"state":         query.Get("state"),
	}
}

// validateOAuthParams validates that required OAuth2 parameters are present
func (h *OAuthProxyHandler) validateOAuthParams(params map[string]string) error {
	if params["response_type"] == "" {
		return fmt.Errorf("missing required parameter: response_type")
	}
	if params["client_id"] == "" {
		return fmt.Errorf("missing required parameter: client_id")
	}
	if params["redirect_uri"] == "" {
		return fmt.Errorf("missing required parameter: redirect_uri")
	}
	return nil
}

// proxyToSpring creates and executes a proxy request to Spring Authorization Server
func (h *OAuthProxyHandler) proxyToSpring(r *http.Request, endpoint string) (*http.Response, error) {
	// Build target URL using centralized configuration
	baseURL := h.endpoints.OAuth2ServerURL
	targetURL := fmt.Sprintf("%s%s?%s", baseURL, endpoint, r.URL.RawQuery)

	// Create HTTP client that doesn't follow redirects (we handle them ourselves)
	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// Create proxy request
	proxyReq, err := http.NewRequest(r.Method, targetURL, r.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to create proxy request: %w", err)
	}

	// Copy headers (excluding cookies which are handled separately)
	h.copyHeaders(proxyReq.Header, r.Header)

	// Forward JSESSIONID cookie if present (maintains session with Spring)
	if cookie, err := r.Cookie("JSESSIONID"); err == nil {
		proxyReq.AddCookie(cookie)
	}

	// Execute request
	return client.Do(proxyReq)
}

// handleAuthorizeRedirect processes redirect responses from authorization endpoint
func (h *OAuthProxyHandler) handleAuthorizeRedirect(w http.ResponseWriter, r *http.Request, resp *http.Response, params map[string]string) {
	location := resp.Header.Get("Location")
	if location == "" {
		http.Error(w, "Invalid redirect response from authorization server", http.StatusInternalServerError)
		return
	}

	parsedLocation, err := url.Parse(location)
	if err != nil {
		log.Printf("ERROR: Failed to parse redirect location '%s': %v", location, err)
		http.Error(w, "Invalid redirect from authorization server", http.StatusInternalServerError)
		return
	}

	// Check if Spring is redirecting to login (user not authenticated)
	if h.isLoginRedirect(parsedLocation.Path) {
		log.Printf("INFO: User not authenticated, initiating BFF login flow")
		h.handleUnauthenticatedUser(w, r, params)
		return
	}

	// Forward other redirects (e.g., authorization code redirect to client)
	log.Printf("INFO: Forwarding redirect to: %s", location)
	h.forwardCookies(w, resp)
	http.Redirect(w, r, location, resp.StatusCode)
}

// isLoginRedirect checks if a path indicates a login redirect
func (h *OAuthProxyHandler) isLoginRedirect(path string) bool {
	loginPaths := []string{"/login", "/closeauth/login", "/auth/login"}
	for _, loginPath := range loginPaths {
		if path == loginPath {
			return true
		}
	}
	return false
}

// handleUnauthenticatedUser saves OAuth context and redirects to BFF login page
func (h *OAuthProxyHandler) handleUnauthenticatedUser(w http.ResponseWriter, r *http.Request, params map[string]string) {
	// Create OAuth context to preserve authorization request parameters
	oauthCtx := &middleware.OAuthContext{
		ResponseType: params["response_type"],
		ClientID:     params["client_id"],
		RedirectURI:  params["redirect_uri"],
		Scope:        params["scope"],
		State:        params["state"],
	}

	// Save context in encrypted cookie
	if err := middleware.SaveOAuthContext(w, oauthCtx); err != nil {
		log.Printf("ERROR: Failed to save OAuth context: %v", err)
		http.Error(w, "Failed to save authorization context", http.StatusInternalServerError)
		return
	}

	log.Printf("INFO: OAuth context saved for client_id=%s, redirecting to login", params["client_id"])

	// Redirect to BFF's custom login page
	http.Redirect(w, r, "/auth/login?continue=true", http.StatusFound)
}

// forwardResponse forwards the complete HTTP response to the client
func (h *OAuthProxyHandler) forwardResponse(w http.ResponseWriter, resp *http.Response) {
	// Copy response headers
	h.copyResponseHeaders(w.Header(), resp.Header)

	// Forward cookies
	h.forwardCookies(w, resp)

	// Set status code
	w.WriteHeader(resp.StatusCode)

	// Copy response body
	if _, err := io.Copy(w, resp.Body); err != nil {
		log.Printf("ERROR: Failed to copy response body: %v", err)
	}
}

// copyHeaders copies HTTP headers from source to destination (excluding cookies)
func (h *OAuthProxyHandler) copyHeaders(dst, src http.Header) {
	for key, values := range src {
		// Skip cookie headers (handled separately for proper transformation)
		if key == "Cookie" || key == "Set-Cookie" {
			continue
		}
		for _, value := range values {
			dst.Add(key, value)
		}
	}
}

// copyResponseHeaders copies response headers (excluding Set-Cookie which needs special handling)
func (h *OAuthProxyHandler) copyResponseHeaders(dst, src http.Header) {
	for key, values := range src {
		if key == "Set-Cookie" {
			continue // Handled separately by forwardCookies
		}
		for _, value := range values {
			dst.Add(key, value)
		}
	}
}

// forwardCookies forwards cookies from Spring to the browser with appropriate security settings
func (h *OAuthProxyHandler) forwardCookies(w http.ResponseWriter, resp *http.Response) {
	for _, cookie := range resp.Cookies() {
		// Create new cookie with BFF-appropriate settings
		newCookie := &http.Cookie{
			Name:     cookie.Name,
			Value:    cookie.Value,
			Path:     cookie.Path,
			Domain:   cookie.Domain,
			MaxAge:   cookie.MaxAge,
			Secure:   isProduction(), // Enable in production with HTTPS
			HttpOnly: true,            // Prevent JavaScript access
			SameSite: http.SameSiteLaxMode,
		}

		// Ensure path is set
		if newCookie.Path == "" {
			newCookie.Path = "/"
		}

		http.SetCookie(w, newCookie)
	}
}

// isRedirect checks if an HTTP status code is a redirect
func (h *OAuthProxyHandler) isRedirect(statusCode int) bool {
	return statusCode >= 300 && statusCode < 400
}

// isProduction checks if the application is running in production mode
func isProduction() bool {
	env := os.Getenv("ENVIRONMENT")
	return env == "production" || env == "prod"
}
