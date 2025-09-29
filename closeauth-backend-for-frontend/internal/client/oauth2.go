package client

import (
	"bytes"
	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/model"
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

type OAuth2Client struct {
	config     *config.OAuthClientConfig
	httpClient *http.Client
	logger     *slog.Logger
}

func NewOAuth2Client(cfg *config.OAuthClientConfig) *OAuth2Client {
	return &OAuth2Client{
		config: cfg,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:       10,
				IdleConnTimeout:    30 * time.Second,
				DisableCompression: false,
			},
		},
		logger: slog.Default().With("component", "oauth2_client"),
	}
}

// validateConfig validates the OAuth2 client configuration
func (c *OAuth2Client) validateConfig() error {
	if c.config == nil {
		return fmt.Errorf("oauth2 config is nil")
	}
	if c.config.OAuth2BaseURL == "" {
		return fmt.Errorf("oauth2 base URL is required")
	}
	if c.config.DefaultClientID == "" {
		return fmt.Errorf("client ID is required")
	}
	if c.config.DefaultClientSecret == "" {
		return fmt.Errorf("client secret is required")
	}
	if c.config.DefaultRedirectURL == "" {
		return fmt.Errorf("redirect URL is required")
	}
	return nil
}

// GetAccessToken retrieves an OAuth2 access token using client credentials flow
func (c *OAuth2Client) GetAccessToken() (*model.AccessTokenResponse, error) {
	return c.GetAccessTokenWithContext(context.Background())
}

// GetAccessTokenWithContext retrieves an OAuth2 access token using client credentials flow with context
func (c *OAuth2Client) GetAccessTokenWithContext(ctx context.Context) (*model.AccessTokenResponse, error) {
	c.logger.Info("Requesting OAuth2 access token",
		"grant_type", "client_credentials",
		"client_id", c.config.DefaultClientID,
		"scope", c.config.DefaultScope)

	// Validate configuration
	if err := c.validateConfig(); err != nil {
		c.logger.Error("Invalid OAuth2 configuration", "error", err)
		return nil, fmt.Errorf("invalid oauth2 configuration: %w", err)
	}

	// Prepare form data
	data := url.Values{}
	data.Set("grant_type", "client_credentials")
	data.Set("client_id", c.config.DefaultClientID)
	data.Set("client_secret", c.config.DefaultClientSecret)
	data.Set("redirect_uri", c.config.DefaultRedirectURL) // Fixed: should be redirect_uri, not redirect_url
	data.Set("scope", c.config.DefaultScope)

	tokenURL := c.config.OAuth2BaseURL + "/closeauth/oauth2/token"
	c.logger.Debug("Making token request", "url", tokenURL)

	req, err := http.NewRequestWithContext(ctx, "POST", tokenURL, strings.NewReader(data.Encode()))
	if err != nil {
		c.logger.Error("Failed to create token request", "error", err)
		return nil, fmt.Errorf("failed to create token request: %w", err)
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	startTime := time.Now()
	resp, err := c.httpClient.Do(req)
	duration := time.Since(startTime)

	if err != nil {
		c.logger.Error("Token request failed",
			"error", err,
			"duration_ms", duration.Milliseconds())
		return nil, fmt.Errorf("failed to make token request: %w", err)
	}
	defer resp.Body.Close()

	c.logger.Debug("Token request completed",
		"status_code", resp.StatusCode,
		"duration_ms", duration.Milliseconds())

	// Read response
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		c.logger.Error("Failed to read token response", "error", err)
		return nil, fmt.Errorf("failed to read token response: %w", err)
	}

	c.logger.Debug("Token response received",
		"status_code", resp.StatusCode,
		"response_size", len(body))

	if resp.StatusCode != http.StatusOK {
		c.logger.Error("Token request failed",
			"status_code", resp.StatusCode,
			"response_body", string(body))
		return nil, fmt.Errorf("token request failed with status %d: %s", resp.StatusCode, string(body))
	}

	var tokenResp model.AccessTokenResponse
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		c.logger.Error("Failed to decode token response",
			"error", err,
			"response_body", string(body))
		return nil, fmt.Errorf("failed to decode token response: %w", err)
	}

	c.logger.Info("Access token retrieved successfully",
		"token_type", tokenResp.TokenType,
		"expires_in", tokenResp.ExpiresIn,
		"scope", tokenResp.Scope)

	return &tokenResp, nil
}

// Client Management APIs

func (c *OAuth2Client) RegisterClient(accessToken string, formReq *model.ClientFormRequest) (*model.ClientRegistrationResponse, error) {
	return c.RegisterClientWithContext(context.Background(), accessToken, formReq)
}

// RegisterClientWithContext registers a new OAuth2 client with context
func (c *OAuth2Client) RegisterClientWithContext(ctx context.Context, accessToken string, formReq *model.ClientFormRequest) (*model.ClientRegistrationResponse, error) {
	c.logger.Info("Registering OAuth2 client",
		"client_name", formReq.ClientName,
		"grant_types", formReq.GrantTypes,
		"redirect_uris_count", len(formReq.RedirectURIs))

	// Validate inputs
	if accessToken == "" {
		c.logger.Error("Access token is required for client registration")
		return nil, fmt.Errorf("access token is required")
	}
	if formReq == nil {
		c.logger.Error("Client form request is nil")
		return nil, fmt.Errorf("client form request is required")
	}
	if err := c.validateClientFormRequest(formReq); err != nil {
		c.logger.Error("Invalid client form request", "error", err)
		return nil, fmt.Errorf("invalid client form request: %w", err)
	}

	regReq := &model.ClientRegistrationRequest{
		ClientName:              formReq.ClientName,
		GrantTypes:              formReq.GrantTypes,
		TokenEndpointAuthMethod: formReq.TokenEndpointAuthMethod,
		Scope:                   formReq.Scope,
		RedirectURIs:           formReq.RedirectURIs,
	}

	// Override scope if provided in form
	if formReq.Scope != "" {
		regReq.Scope = formReq.Scope
	}

	jsonData, err := json.Marshal(regReq)
	if err != nil {
		c.logger.Error("Failed to marshal registration request", "error", err)
		return nil, fmt.Errorf("failed to marshal registration request: %w", err)
	}

	registrationURL := c.config.OAuth2BaseURL + "/closeauth/connect/register"
	c.logger.Debug("Making client registration request", "url", registrationURL)

	req, err := http.NewRequestWithContext(ctx, "POST", registrationURL, bytes.NewBuffer(jsonData))
	if err != nil {
		c.logger.Error("Failed to create registration request", "error", err)
		return nil, fmt.Errorf("failed to create registration request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	startTime := time.Now()
	resp, err := c.httpClient.Do(req)
	duration := time.Since(startTime)

	if err != nil {
		c.logger.Error("Client registration request failed",
			"error", err,
			"duration_ms", duration.Milliseconds())
		return nil, fmt.Errorf("registration request failed: %w", err)
	}
	defer resp.Body.Close()

	c.logger.Debug("Client registration request completed",
		"status_code", resp.StatusCode,
		"duration_ms", duration.Milliseconds())

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		c.logger.Error("Failed to read registration response", "error", err)
		return nil, fmt.Errorf("failed to read registration response: %w", err)
	}

	c.logger.Debug("Client registration response received",
		"status_code", resp.StatusCode,
		"response_size", len(body))

	var regResp model.ClientRegistrationResponse
	if err := json.Unmarshal(body, &regResp); err != nil {
		c.logger.Error("Failed to decode registration response",
			"error", err,
			"response_body", string(body))
		return nil, fmt.Errorf("failed to decode registration response: %w", err)
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		// Try to parse error response
		var errResp model.ClientRegistrationError
		if err := json.Unmarshal(body, &errResp); err == nil && errResp.Error != "" {
			c.logger.Error("Client registration failed",
				"error", errResp.Error,
				"error_description", errResp.ErrorDescription,
				"status_code", resp.StatusCode)
			return nil, fmt.Errorf("client registration failed: %s - %s", errResp.Error, errResp.ErrorDescription)
		}
		c.logger.Error("Registration failed",
			"status_code", resp.StatusCode,
			"response_body", string(body))
		return nil, fmt.Errorf("registration failed with status %d: %s", resp.StatusCode, string(body))
	}

	c.logger.Info("Client registered successfully",
		"client_id", regResp.ClientID,
		"client_name", regResp.ClientName,
		"grant_types", regResp.GrantTypes)

	return &regResp, nil
}

// validateClientFormRequest validates the client form request
func (c *OAuth2Client) validateClientFormRequest(req *model.ClientFormRequest) error {
	if req.ClientName == "" {
		return fmt.Errorf("client name is required")
	}
	if len(req.RedirectURIs) == 0 {
		return fmt.Errorf("at least one redirect URI is required")
	}
	for i, uri := range req.RedirectURIs {
		if uri == "" {
			return fmt.Errorf("redirect URI at index %d is empty", i)
		}
		if _, err := url.Parse(uri); err != nil {
			return fmt.Errorf("invalid redirect URI at index %d: %w", i, err)
		}
	}
	return nil
}


