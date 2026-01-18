package oauth

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"

	"closeauth-backend-for-frontend/internal/handlers/response"
	"closeauth-backend-for-frontend/internal/middleware"
	templates "closeauth-backend-for-frontend/internal/templates/layouts"

	"github.com/a-h/templ"
)

// HandleOAuthConsentGet renders the OAuth consent page
// Spring Authorization Server redirects here: /oauth/consent?scope=...&client_id=...&state=...
func (h *OAuthClientAuthHandler) HandleOAuthConsentGet(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters from Spring Authorization Server
	clientID := r.URL.Query().Get("client_id")
	scopeParam := r.URL.Query().Get("scope")
	state := r.URL.Query().Get("state")

	h.logger.Info("OAuth consent page requested", "client_id", clientID, "scope", scopeParam, "state", state)

	// Get OAuth context to retrieve username and redirect_uri
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		h.logger.Warn("could not retrieve OAuth context for consent", "error", err)
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

	if clientID != "" && h.authenticatedClient != nil {
		clientInfo, err := h.authenticatedClient.GetClientInfo(r.Context(), clientID)
		if err != nil {
			h.logger.Warn("failed to fetch client info for consent page", "client_id", clientID, "error", err)
		} else {
			if clientInfo.ClientName != "" {
				clientName = clientInfo.ClientName
			}
			if clientInfo.LogoURI != "" {
				themeData.LogoURL = &clientInfo.LogoURI
			}
		}
	}

	// Split scopes for display
	scopeStrings := strings.Split(scopeParam, " ")
	scopes := templates.MapScopesToDisplay(scopeStrings)

	// Build consent data
	consentData := templates.OAuthConsentData{
		CSRFToken:   csrfToken,
		Theme:       convertThemeToThemeData(themeData),
		ClientName:  clientName,
		ClientID:    clientID,
		Username:    username,
		RedirectURI: redirectURI,
		Scopes:      scopes,
		State:       state,
		ErrorMsg:    "",
	}

	// Render the OAuth consent template
	component := templates.OAuthConsent(consentData)
	templ.Handler(component).ServeHTTP(w, r)
}

// HandleOAuthConsentPost handles the user's decision on the consent page
func (h *OAuthClientAuthHandler) HandleOAuthConsentPost(w http.ResponseWriter, r *http.Request) {
	// Parse form data
	form, err := middleware.NewFormData(r)
	if err != nil {
		response.RenderError(w, r, "Failed to parse form data", http.StatusBadRequest)
		return
	}

	// Extract form values
	clientID := form.Get("client_id")
	state := form.Get("state")
	consent := form.Get("consent") // "approve" or "deny"
	scopes := r.Form["scope"]      // Array of selected scopes

	h.logger.Info("OAuth consent form submitted", "client_id", clientID, "state", state, "consent", consent, "scopes", scopes)

	// Get OAuth context to retrieve the Spring session ID
	oauthCtx, err := middleware.GetOAuthContext(r)
	if err != nil {
		h.logger.Error("failed to retrieve OAuth context for consent submission", "error", err)
		response.RenderError(w, r, "Your session has expired. Please start the login process again.", http.StatusBadRequest)
		return
	}

	// Prepare the form data to be sent to the Spring Authorization Server's consent endpoint
	formData := url.Values{}
	formData.Set("client_id", clientID)
	formData.Set("state", state)

	if consent == "approve" {
		for _, scope := range scopes {
			formData.Add("scope", scope)
		}
	} else {
		// If denied, we don't add any scopes, which Spring interprets as denial
	}

	consentURL := h.endpoints.GetConsentURL()

	// Create a direct HTTP request to Spring's consent endpoint
	httpClient := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // We want to capture the redirect location
		},
	}

	req, err := http.NewRequest("POST", consentURL, strings.NewReader(formData.Encode()))
	if err != nil {
		h.logger.Error("failed to create consent request", "error", err)
		response.RenderError(w, r, "Consent service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	// Include the Spring session ID from the context
	if oauthCtx.SpringSessionID != "" {
		req.AddCookie(&http.Cookie{
			Name:  "JSESSIONID",
			Value: oauthCtx.SpringSessionID,
		})
		h.logger.Debug("attached Spring JSESSIONID to consent request")
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		h.logger.Error("failed to call consent service", "error", err)
		response.RenderError(w, r, "Consent service unavailable. Please try again later.", http.StatusServiceUnavailable)
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	h.logger.Debug("consent service response", "status", resp.StatusCode, "body", string(body))

	// After consent, the OAuth context is no longer needed
	middleware.ClearOAuthContext(w)

	// Check for redirect
	if resp.StatusCode == http.StatusFound {
		redirectURL := resp.Header.Get("Location")
		h.logger.Info("redirecting after consent", "url", redirectURL)

		// Handle HTMX vs standard redirect
		if middleware.IsHTMXRequest(r) {
			middleware.HTMXRedirect(w, redirectURL)
		} else {
			http.Redirect(w, r, redirectURL, http.StatusSeeOther)
		}
		return
	}

	// Handle other responses
	var errorResp struct {
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	if err := json.Unmarshal(body, &errorResp); err == nil {
		h.logger.Error("consent service returned an error", "error", errorResp.Error, "description", errorResp.ErrorDescription)
		response.RenderError(w, r, errorResp.ErrorDescription, http.StatusInternalServerError)
		return
	}

	h.logger.Error("unexpected response from consent service", "status", resp.StatusCode)
	response.RenderError(w, r, "An unexpected error occurred during the consent process.", http.StatusInternalServerError)
}
