package client

import (
	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/model"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type OAuth2Client struct {
	config     *config.OAuthClientConfig
	httpClient *http.Client
}

func NewOAuth2Client(cfg *config.OAuthClientConfig) *OAuth2Client {
	return &OAuth2Client{
		config: cfg,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}


// GetAccessToken retrieves an OAuth2 access token using client credentials flow

func (c *OAuth2Client) GetAccessToken()(*model.AccessTokenResponse, error){
	// Prepare form data
	data := url.Values{}

	data.Set("grant_type", "client_credentials")
	data.Set("client_id", c.config.DefaultClientID)
	data.Set("client_secret", c.config.DefaultClientSecret)
	data.Set("redirect_url", c.config.DefaultRedirectURL)
	data.Set("scope", c.config.DefaultScope)

	resp, err :=c.httpClient.Post(
		c.config.OAuth2BaseURL+"/closeauth/oauth2/token",
		"application/x-www-form-urlencoded",
		strings.NewReader(data.Encode()),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to make token request: %w", err)
	}
	defer resp.Body.Close()
		// Read response
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read token response: %w", err)
	}

	var tokenResp model.AccessTokenResponse
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		return nil, fmt.Errorf("failed to decode token response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("token request failed: %s", body)
	}

	return &tokenResp, nil
}



