package client

import (
	"bytes"
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

// Client Management APIs

func (c *OAuth2Client) RegisterClient(accessToken string, formReq *model.ClientFormRequest) (*model.ClientRegistrationResponse, error) {

	regReq := &model.ClientRegistrationRequest{
        ClientName:                 formReq.ClientName,
        GrantTypes:                 formReq.GrantTypes,
        TokenEndpointAuthMethod:    formReq.TokenEndpointAuthMethod,
        Scope:                      formReq.Scope,
        RedirectURIs:              formReq.RedirectURIs,
    }

	    // Override scope if provided in form
    if formReq.Scope != "" {
        regReq.Scope = formReq.Scope
    }
    
    jsonData, err := json.Marshal(regReq)
    if err != nil {
        return nil, fmt.Errorf("failed to marshal registration request: %w", err)
    }

	req, err := http.NewRequest(
		"POST",
		c.config.OAuth2BaseURL+"/closeauth/connect/register",
		bytes.NewBuffer(jsonData),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create registration request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
    if err != nil {
        return nil, fmt.Errorf("registration request failed: %w", err)
    }
    defer resp.Body.Close()
    
	body, err := io.ReadAll(resp.Body)
    if err != nil {
        return nil, fmt.Errorf("failed to read registration response: %w", err)
    }
    
    var regResp model.ClientRegistrationResponse
    if err := json.Unmarshal(body, &regResp); err != nil {
        return nil, fmt.Errorf("failed to decode registration response: %w", err)
    }
    
    if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
        if regResp.Error != "" {
            return nil, fmt.Errorf("client registration failed: %s - %s", regResp.Error, regResp.ErrorDescription)
        }
        return nil, fmt.Errorf("registration failed with status %d: %s", resp.StatusCode, string(body))
    }
    
    return &regResp, nil
}


