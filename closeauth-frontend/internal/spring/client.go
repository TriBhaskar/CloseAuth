package spring

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// SpringClient communicates with the Spring Authorization Server.
// All methods accept context.Context for cancellation/timeout control.
type SpringClient struct {
	config       *Config
	tokenManager *TokenManager
	httpClient   *http.Client
	logger       *slog.Logger
}

// NewSpringClient creates a new Spring client with a shared HTTP client that
// does NOT follow redirects automatically (we handle them ourselves).
func NewSpringClient(cfg *Config, tokenManager *TokenManager, logger *slog.Logger) *SpringClient {
	client := &SpringClient{
		config:       cfg,
		tokenManager: tokenManager,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:       20,
				IdleConnTimeout:    30 * time.Second,
				DisableCompression: false,
			},
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse // Never auto-follow redirects
			},
		},
		logger: logger.With("component", "spring_client"),
	}

	// Wire up the circular reference
	tokenManager.SetClient(client)

	return client
}

// --- Token Operations ---

// fetchAccessToken retrieves an access token using client_credentials grant.
// Called internally by TokenManager; not intended for direct use.
func (c *SpringClient) fetchAccessToken(ctx context.Context) (*AccessTokenResponse, error) {
	data := url.Values{}
	data.Set("grant_type", "client_credentials")
	data.Set("client_id", c.config.DefaultClientID)
	data.Set("client_secret", c.config.DefaultClientSecret)
	data.Set("redirect_uri", c.config.DefaultRedirectURL)
	data.Set("scope", c.config.DefaultScope)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.config.TokenURL(), strings.NewReader(data.Encode()))
	if err != nil {
		return nil, fmt.Errorf("create token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute token request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read token response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("token request failed (status %d): %s", resp.StatusCode, string(body))
	}

	var tokenResp AccessTokenResponse
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		return nil, fmt.Errorf("decode token response: %w", err)
	}

	return &tokenResp, nil
}

// --- Client Registration ---

// RegisterClient registers a new OAuth2 client with Spring.
func (c *SpringClient) RegisterClient(ctx context.Context, accessToken string, formReq *ClientFormRequest) (*ClientRegistrationResponse, error) {
	if accessToken == "" {
		return nil, fmt.Errorf("access token is required")
	}
	if formReq == nil || formReq.ClientName == "" {
		return nil, fmt.Errorf("client name is required")
	}
	if len(formReq.RedirectURIs) == 0 {
		return nil, fmt.Errorf("at least one redirect URI is required")
	}

	regReq := &ClientRegistrationRequest{
		ClientName:              formReq.ClientName,
		GrantTypes:              formReq.GrantTypes,
		TokenEndpointAuthMethod: formReq.TokenEndpointAuthMethod,
		Scope:                   formReq.Scope,
		RedirectURIs:            formReq.RedirectURIs,
	}

	jsonData, err := json.Marshal(regReq)
	if err != nil {
		return nil, fmt.Errorf("marshal registration request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.config.RegisterClientURL(), bytes.NewReader(jsonData))
	if err != nil {
		return nil, fmt.Errorf("create registration request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute registration request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read registration response: %w", err)
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		var errResp ClientRegistrationError
		if json.Unmarshal(body, &errResp) == nil && errResp.Error != "" {
			return nil, fmt.Errorf("registration failed: %s - %s", errResp.Error, errResp.ErrorDescription)
		}
		return nil, fmt.Errorf("registration failed (status %d): %s", resp.StatusCode, string(body))
	}

	var regResp ClientRegistrationResponse
	if err := json.Unmarshal(body, &regResp); err != nil {
		return nil, fmt.Errorf("decode registration response: %w", err)
	}

	c.logger.Info("client registered successfully", "client_id", regResp.ClientID, "client_name", regResp.ClientName)
	return &regResp, nil
}

// --- Client Info ---

// GetClientInfo fetches client metadata (name, logo, scopes) from Spring.
// Automatically authenticates using the TokenManager.
func (c *SpringClient) GetClientInfo(ctx context.Context, clientID string) (*ClientInfoResponse, error) {
	if clientID == "" {
		return nil, fmt.Errorf("client ID is required")
	}

	token, err := c.tokenManager.GetValidToken(ctx)
	if err != nil {
		return nil, fmt.Errorf("get access token for client info: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.config.ClientInfoURL(clientID), nil)
	if err != nil {
		return nil, fmt.Errorf("create client info request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute client info request: %w", err)
	}
	defer resp.Body.Close()

	// Retry once on 401 (token may have expired between check and use)
	if resp.StatusCode == http.StatusUnauthorized {
		resp.Body.Close()
		c.tokenManager.InvalidateToken()

		token, err = c.tokenManager.GetValidToken(ctx)
		if err != nil {
			return nil, fmt.Errorf("get fresh token after 401: %w", err)
		}

		req, _ = http.NewRequestWithContext(ctx, http.MethodGet, c.config.ClientInfoURL(clientID), nil)
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Accept", "application/json")

		resp, err = c.httpClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("retry client info request: %w", err)
		}
		defer resp.Body.Close()
	}

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("client info request failed (status %d): %s", resp.StatusCode, string(body))
	}

	var info ClientInfoResponse
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, fmt.Errorf("decode client info: %w", err)
	}

	return &info, nil
}

// --- OAuth2 Proxy ---

// ProxyAuthorize proxies an authorization request to Spring and returns the result.
// The caller decides whether to http.Redirect() or return JSON based on context.
func (c *SpringClient) ProxyAuthorize(ctx context.Context, queryParams string, jsessionID string) (*ProxyResult, error) {
	targetURL := c.config.AuthorizeURL() + "?" + queryParams

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
	if err != nil {
		return nil, fmt.Errorf("create authorize proxy request: %w", err)
	}

	if jsessionID != "" {
		req.AddCookie(&http.Cookie{Name: "JSESSIONID", Value: jsessionID})
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute authorize proxy request: %w", err)
	}
	defer resp.Body.Close()

	result := &ProxyResult{
		StatusCode: resp.StatusCode,
		Location:   resp.Header.Get("Location"),
		Cookies:    extractCookies(resp),
	}

	if !isRedirect(resp.StatusCode) {
		result.Body, _ = io.ReadAll(resp.Body)
	}

	return result, nil
}

// SubmitLogin sends user credentials to Spring's login endpoint.
func (c *SpringClient) SubmitLogin(ctx context.Context, username, password, jsessionID string) (*ProxyResult, error) {
	formData := url.Values{}
	formData.Set("username", username)
	formData.Set("password", password)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.config.LoginURL(), strings.NewReader(formData.Encode()))
	if err != nil {
		return nil, fmt.Errorf("create login request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	if jsessionID != "" {
		req.AddCookie(&http.Cookie{Name: "JSESSIONID", Value: jsessionID})
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute login request: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	return &ProxyResult{
		StatusCode: resp.StatusCode,
		Location:   resp.Header.Get("Location"),
		Body:       body,
		Cookies:    extractCookies(resp),
	}, nil
}

// SubmitConsent sends the user's consent decision to Spring.
func (c *SpringClient) SubmitConsent(ctx context.Context, clientID, state string, scopes []string, jsessionID string) (*ProxyResult, error) {
	formData := url.Values{}
	formData.Set("client_id", clientID)
	formData.Set("state", state)
	for _, scope := range scopes {
		formData.Add("scope", scope)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.config.ConsentURL(), strings.NewReader(formData.Encode()))
	if err != nil {
		return nil, fmt.Errorf("create consent request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	if jsessionID != "" {
		req.AddCookie(&http.Cookie{Name: "JSESSIONID", Value: jsessionID})
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute consent request: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	return &ProxyResult{
		StatusCode: resp.StatusCode,
		Location:   resp.Header.Get("Location"),
		Body:       body,
		Cookies:    extractCookies(resp),
	}, nil
}

// --- Admin Auth Proxy ---

// ProxyAdminAuth proxies a request to a Spring admin auth endpoint and returns the raw response.
func (c *SpringClient) ProxyAdminAuth(ctx context.Context, method, fullURL string, jsonBody []byte) (*ProxyResult, error) {
	var bodyReader io.Reader
	if jsonBody != nil {
		bodyReader = bytes.NewReader(jsonBody)
	}

	req, err := http.NewRequestWithContext(ctx, method, fullURL, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("create admin auth request: %w", err)
	}

	if jsonBody != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute admin auth request: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	return &ProxyResult{
		StatusCode: resp.StatusCode,
		Body:       body,
		Cookies:    extractCookies(resp),
	}, nil
}

// --- Helpers ---

func extractCookies(resp *http.Response) []*Cookie {
	var cookies []*Cookie
	for _, c := range resp.Cookies() {
		cookies = append(cookies, &Cookie{
			Name:   c.Name,
			Value:  c.Value,
			Path:   c.Path,
			MaxAge: c.MaxAge,
		})
	}
	return cookies
}

func isRedirect(statusCode int) bool {
	return statusCode >= 300 && statusCode < 400
}
