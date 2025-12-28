package service

import (
	"bytes"
	"closeauth-backend-for-frontend/internal/config"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

// AuthenticatedClient is an HTTP client that automatically includes OAuth2 bearer tokens
// for CloseAuth admin API endpoints
type AuthenticatedClient struct {
	tokenManager *TokenManager
	httpClient   *http.Client
	endpoints    *config.EndpointsConfig
	logger       *slog.Logger
}

// NewAuthenticatedClient creates a new authenticated HTTP client
func NewAuthenticatedClient(tokenManager *TokenManager, endpoints *config.EndpointsConfig) *AuthenticatedClient {
	return &AuthenticatedClient{
		tokenManager: tokenManager,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:       10,
				IdleConnTimeout:    30 * time.Second,
				DisableCompression: false,
			},
		},
		endpoints: endpoints,
		logger:    slog.Default().With("component", "authenticated_client"),
	}
}

// Post sends a POST request with automatic OAuth2 authentication
func (ac *AuthenticatedClient) Post(ctx context.Context, url string, contentType string, body io.Reader) (*http.Response, error) {
	return ac.doRequest(ctx, "POST", url, contentType, body)
}

// Get sends a GET request with automatic OAuth2 authentication
func (ac *AuthenticatedClient) Get(ctx context.Context, url string) (*http.Response, error) {
	return ac.doRequest(ctx, "GET", url, "", nil)
}

// doRequest performs an HTTP request with automatic OAuth2 bearer token injection
// If a 401 response is received, it will retry once with a fresh token
func (ac *AuthenticatedClient) doRequest(ctx context.Context, method, url, contentType string, body io.Reader) (*http.Response, error) {
	// Get valid access token
	token, err := ac.tokenManager.GetValidToken(ctx)
	if err != nil {
		ac.logger.Error("Failed to get access token", "error", err)
		return nil, fmt.Errorf("failed to get access token: %w", err)
	}

	// Read body content if present (we might need to retry)
	var bodyBytes []byte
	if body != nil {
		bodyBytes, err = io.ReadAll(body)
		if err != nil {
			return nil, fmt.Errorf("failed to read request body: %w", err)
		}
	}

	// Try request with current token
	resp, err := ac.makeRequest(ctx, method, url, contentType, token, bodyBytes)
	if err != nil {
		return nil, err
	}

	// If we get a 401, invalidate the token and retry once
	if resp.StatusCode == http.StatusUnauthorized {
		ac.logger.Warn("Received 401 Unauthorized, invalidating token and retrying",
			"url", url,
			"method", method)
		
		// Close the failed response
		resp.Body.Close()
		
		// Invalidate current token
		ac.tokenManager.InvalidateToken()
		
		// Get fresh token
		token, err = ac.tokenManager.GetValidToken(ctx)
		if err != nil {
			ac.logger.Error("Failed to get fresh access token after 401", "error", err)
			return nil, fmt.Errorf("failed to get fresh access token: %w", err)
		}
		
		// Retry with fresh token
		ac.logger.Info("Retrying request with fresh token",
			"url", url,
			"method", method)
		resp, err = ac.makeRequest(ctx, method, url, contentType, token, bodyBytes)
		if err != nil {
			return nil, err
		}
	}

	return resp, nil
}

// makeRequest creates and executes an HTTP request with the provided bearer token
func (ac *AuthenticatedClient) makeRequest(ctx context.Context, method, url, contentType, token string, bodyBytes []byte) (*http.Response, error) {
	var bodyReader io.Reader
	if bodyBytes != nil {
		bodyReader = bytes.NewReader(bodyBytes)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		ac.logger.Error("Failed to create request", "error", err, "url", url)
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	// Set Authorization header with Bearer token
	req.Header.Set("Authorization", "Bearer "+token)
	
	// Set content type if provided
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	
	// Set Accept header
	req.Header.Set("Accept", "application/json")

	ac.logger.Debug("Making authenticated request",
		"method", method,
		"url", url,
		"has_body", bodyBytes != nil)

	startTime := time.Now()
	resp, err := ac.httpClient.Do(req)
	duration := time.Since(startTime)

	if err != nil {
		ac.logger.Error("Request failed",
			"error", err,
			"method", method,
			"url", url,
			"duration_ms", duration.Milliseconds())
		return nil, fmt.Errorf("request failed: %w", err)
	}

	ac.logger.Debug("Request completed",
		"method", method,
		"url", url,
		"status_code", resp.StatusCode,
		"duration_ms", duration.Milliseconds())

	return resp, nil
}

// PostJSON sends a POST request with JSON body and automatic authentication
func (ac *AuthenticatedClient) PostJSON(ctx context.Context, url string, jsonBody []byte) (*http.Response, error) {
	return ac.Post(ctx, url, "application/json", bytes.NewReader(jsonBody))
}

// PostForm sends a POST request with form data and automatic authentication
// Note: Most CloseAuth admin endpoints expect JSON, not form data
func (ac *AuthenticatedClient) PostForm(ctx context.Context, url string, formData io.Reader) (*http.Response, error) {
	return ac.Post(ctx, url, "application/x-www-form-urlencoded", formData)
}

// GetClientInfo fetches client information (name, logo, scopes) from Spring Authorization Server
func (ac *AuthenticatedClient) GetClientInfo(ctx context.Context, clientID string) (*ClientInfoResult, error) {
	if clientID == "" {
		return nil, fmt.Errorf("client ID is required")
	}

	url := ac.endpoints.GetClientInfoURL(clientID)
	ac.logger.Debug("Fetching client info", "client_id", clientID, "url", url)

	resp, err := ac.Get(ctx, url)
	if err != nil {
		ac.logger.Error("Failed to fetch client info", "error", err, "client_id", clientID)
		return nil, fmt.Errorf("failed to fetch client info: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		ac.logger.Error("Client info request failed",
			"status_code", resp.StatusCode,
			"client_id", clientID,
			"response", string(body))
		return nil, fmt.Errorf("client info request failed with status %d", resp.StatusCode)
	}

	var clientInfo ClientInfoResult
	if err := decodeJSON(resp.Body, &clientInfo); err != nil {
		ac.logger.Error("Failed to decode client info response", "error", err)
		return nil, fmt.Errorf("failed to decode client info: %w", err)
	}

	ac.logger.Info("Client info fetched successfully",
		"client_id", clientInfo.ClientID,
		"client_name", clientInfo.ClientName)

	return &clientInfo, nil
}

// ClientInfoResult represents the response from the client-info endpoint
type ClientInfoResult struct {
	ClientID   string   `json:"clientId"`
	ClientName string   `json:"clientName"`
	LogoURI    string   `json:"logoUri"`
	Scopes     []string `json:"scopes"`
}

// decodeJSON is a helper to decode JSON response
func decodeJSON(r io.Reader, v interface{}) error {
	return json.NewDecoder(r).Decode(v)
}
