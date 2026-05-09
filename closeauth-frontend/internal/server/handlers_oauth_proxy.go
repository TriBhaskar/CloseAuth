package server

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"closeauth-frontend/internal/middleware"
	"closeauth-frontend/internal/spring"
)

// ──────────────────────────────────────────────────────────────────────────────
// OAuth Proxy Handlers (browser-navigation routes with native http.Redirect)
// ──────────────────────────────────────────────────────────────────────────────

// handleAuthorizeImpl proxies OAuth2 authorization requests to Spring.
//
// This is hit by BROWSER NAVIGATION (not SPA fetch):
//   - External client redirects user here
//   - Vue does window.location.href after login success
//
// Flow:
//  1. Proxy GET to Spring /oauth2/authorize
//  2. If Spring 302 → login: save OAuthContext cookie, redirect to /oauth/login
//  3. If Spring 302 → consent: redirect to /oauth/consent
//  4. If Spring 302 → external client: redirect to client callback with auth code
func (s *Server) handleAuthorizeImpl(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "authorize")

	// Validate required OAuth params
	query := r.URL.Query()
	responseType := query.Get("response_type")
	clientID := query.Get("client_id")
	redirectURI := query.Get("redirect_uri")

	if responseType == "" || clientID == "" || redirectURI == "" {
		http.Error(w, "Missing required OAuth2 parameters", http.StatusBadRequest)
		return
	}

	// Check if we have an existing OAuthContext with JSESSIONID (resuming after login)
	var jsessionID string
	if oauthCtx, err := middleware.GetOAuthContext(r); err == nil && oauthCtx.SpringSessionID != "" {
		jsessionID = oauthCtx.SpringSessionID
		logger.Debug("using JSESSIONID from OAuthContext", "session_id_len", len(jsessionID))
	} else if cookie, err := r.Cookie("JSESSIONID"); err == nil {
		jsessionID = cookie.Value
		logger.Debug("using JSESSIONID from browser cookie")
	}

	// Proxy to Spring Authorization Server
	result, err := s.springClient.ProxyAuthorize(r.Context(), r.URL.RawQuery, jsessionID)
	if err != nil {
		logger.Error("proxy authorize failed", "error", err)
		http.Error(w, "Authorization service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Forward any cookies from Spring to the browser
	forwardSpringCookies(w, result.Cookies, s.springConfig.IsProduction())

	// Handle redirect responses
	if result.StatusCode >= 300 && result.StatusCode < 400 && result.Location != "" {
		s.handleAuthorizeRedirect(w, r, result, query, logger)
		return
	}

	// Non-redirect response — forward directly
	w.WriteHeader(result.StatusCode)
	if result.Body != nil {
		w.Write(result.Body)
	}
}

// handleAuthorizeRedirect processes redirect responses from Spring's authorize endpoint.
func (s *Server) handleAuthorizeRedirect(w http.ResponseWriter, r *http.Request, result *spring.ProxyResult, query url.Values, logger *slog.Logger) {
	location := result.Location

	parsedLocation, err := url.Parse(location)
	if err != nil {
		logger.Error("failed to parse redirect location", "location", location, "error", err)
		http.Error(w, "Invalid redirect from authorization server", http.StatusInternalServerError)
		return
	}

	logger.Debug("authorize redirect received", "location", location, "path", parsedLocation.Path)

	// Case 1: Spring redirecting to login (user not authenticated)
	if isLoginRedirect(parsedLocation.Path) {
		logger.Info("user not authenticated, redirecting to BFF login")
		s.handleUnauthenticatedRedirect(w, r, result, query)
		return
	}

	// Case 2: Spring redirecting to consent page
	if isConsentRedirect(parsedLocation.Path) {
		consentURL := "/oauth/consent"
		if parsedLocation.RawQuery != "" {
			consentURL += "?" + parsedLocation.RawQuery
		}
		logger.Info("redirecting to consent page", "url", consentURL)
		http.Redirect(w, r, consentURL, http.StatusFound)
		return
	}

	// Case 3: External redirect (auth code to client app)
	logger.Info("redirecting to external client", "location", location)
	http.Redirect(w, r, location, result.StatusCode)
}

// handleUnauthenticatedRedirect saves OAuth context and redirects to BFF login.
func (s *Server) handleUnauthenticatedRedirect(w http.ResponseWriter, r *http.Request, result *spring.ProxyResult, query url.Values) {
	// Extract JSESSIONID from Spring's response
	var springSessionID string
	for _, c := range result.Cookies {
		if c.Name == "JSESSIONID" {
			springSessionID = c.Value
			break
		}
	}

	// Preserve username from existing context if present
	var username string
	if existingCtx, err := middleware.GetOAuthContext(r); err == nil && existingCtx != nil {
		username = existingCtx.Username
	}

	// Save OAuth context in encrypted cookie
	oauthCtx := &middleware.OAuthContext{
		ResponseType:    query.Get("response_type"),
		ClientID:        query.Get("client_id"),
		RedirectURI:     query.Get("redirect_uri"),
		Scope:           query.Get("scope"),
		State:           query.Get("state"),
		SpringSessionID: springSessionID,
		Username:        username,
	}

	if err := middleware.SaveOAuthContext(w, oauthCtx, s.springConfig.IsProduction()); err != nil {
		s.logger.Error("failed to save OAuth context", "error", err)
		http.Error(w, "Failed to save authorization context", http.StatusInternalServerError)
		return
	}

	// Redirect to Vue SPA OAuth login page
	http.Redirect(w, r, "/oauth/login?continue=true", http.StatusFound)
}

// handleTokenImpl proxies OAuth2 token requests to Spring.
// Uses ProxyRaw to forward the client's own credentials (Authorization header / POST body)
// without injecting the BFF's bearer token.
func (s *Server) handleTokenImpl(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request", http.StatusBadRequest)
		return
	}

	targetURL := s.springConfig.TokenURL()
	if r.URL.RawQuery != "" {
		targetURL += "?" + r.URL.RawQuery
	}

	result, err := s.springClient.ProxyRaw(r.Context(), http.MethodPost, targetURL, body, r.Header)
	if err != nil {
		s.logger.Error("token proxy failed", "error", err)
		http.Error(w, "Authorization service unavailable", http.StatusServiceUnavailable)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.StatusCode)
	w.Write(result.Body)
}

// handleConsentPostImpl processes consent form submission (native HTML form POST).
// Redirects the browser directly to the external client's redirect_uri.
func (s *Server) handleConsentPostImpl(w http.ResponseWriter, r *http.Request) {
	logger := s.logger.With("handler", "consent_post")

	if err := r.ParseForm(); err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	clientID := r.FormValue("client_id")
	state := r.FormValue("state")
	consent := r.FormValue("consent")
	scopes := r.Form["scope"]

	logger.Info("consent form submitted", "client_id", clientID, "consent", consent, "scopes", scopes)

	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		logger.Error("failed to get OAuth context for consent", "error", err)
		http.Error(w, "Session expired. Please start the login process again.", http.StatusBadRequest)
		return
	}

	var submittedScopes []string
	if consent == "approve" {
		submittedScopes = scopes
	}

	result, err := s.springClient.SubmitConsent(r.Context(), clientID, state, submittedScopes, oauthCtx.SpringSessionID)
	if err != nil {
		logger.Error("consent proxy failed", "error", err)
		http.Error(w, "Consent service unavailable", http.StatusServiceUnavailable)
		return
	}

	// Clear OAuth context — flow is complete
	middleware.ClearOAuthContext(w)

	// Spring should return 302 to client redirect_uri with auth code
	if result.StatusCode == http.StatusFound && result.Location != "" {
		logger.Info("consent complete, redirecting to client", "location", result.Location)
		http.Redirect(w, r, result.Location, http.StatusSeeOther)
		return
	}

	// Handle error
	if result.Body != nil {
		var errResp struct {
			Error            string `json:"error"`
			ErrorDescription string `json:"error_description"`
		}
		if json.Unmarshal(result.Body, &errResp) == nil && errResp.Error != "" {
			logger.Error("consent failed", "error", errResp.Error)
			http.Error(w, fmt.Sprintf("Consent failed: %s", errResp.ErrorDescription), http.StatusInternalServerError)
			return
		}
	}

	logger.Error("unexpected consent response", "status", result.StatusCode)
	http.Error(w, "Unexpected error during consent", http.StatusInternalServerError)
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

func isLoginRedirect(path string) bool {
	loginPaths := []string{"/oauth/login", "oauth/login", "/login", "/auth/login"}
	for _, lp := range loginPaths {
		if path == lp || strings.HasSuffix(path, lp) {
			return true
		}
	}
	return false
}

func isConsentRedirect(path string) bool {
	return strings.Contains(path, "consent")
}

func forwardSpringCookies(w http.ResponseWriter, cookies []*spring.Cookie, isProduction bool) {
	for _, c := range cookies {
		path := c.Path
		if path == "" {
			path = "/"
		}
		http.SetCookie(w, &http.Cookie{
			Name:     c.Name,
			Value:    c.Value,
			Path:     path,
			MaxAge:   c.MaxAge,
			HttpOnly: true,
			Secure:   isProduction,
			SameSite: http.SameSiteLaxMode,
		})
	}
}
