package client

import (
	"closeauth-backend-for-frontend/internal/sas/config"
	"closeauth-backend-for-frontend/internal/sas/model"
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

func TestOAuth2Client_GetAccessToken_Integration(t *testing.T) {
	// Skip if running unit tests only
	if testing.Short() {
		t.Skip("Skipping integration test")
	}

	// Load configuration from environment or use test defaults
	cfg := &config.OAuthClientConfig{
		OAuth2BaseURL:       getTestEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
		DefaultClientID:     getTestEnv("TEST_CLIENT_ID", "test1"),
		DefaultClientSecret: getTestEnv("TEST_CLIENT_SECRET", "test1"),
		DefaultRedirectURL:  getTestEnv("TEST_REDIRECT_URL", "http://127.0.0.1:8083/login/oauth2/code/public-client-react"),
		DefaultScope:        getTestEnv("TEST_SCOPE", "client.create"),
	}

	t.Logf("Config values: %+v", cfg)

	client := NewOAuth2Client(cfg)

	t.Run("Successful token request", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		token, err := client.GetAccessTokenWithContext(ctx)

		if err != nil {
			t.Fatalf("Expected no error, got: %v", err)
		}

		if token == nil {
			t.Fatal("Expected token response, got nil")
		}

		// Validate token response fields
		if token.AccessToken == "" {
			t.Error("Expected access_token to be non-empty")
		}

		if token.TokenType == "" {
			t.Error("Expected token_type to be non-empty")
		} else if !strings.EqualFold(token.TokenType, "bearer") {
			t.Errorf("Expected token_type to be 'bearer', got: %s", token.TokenType)
		}

		if token.ExpiresIn <= 0 {
			t.Error("Expected expires_in to be greater than 0")
		}

		if len(token.AccessToken) < 10 {
			t.Error("Access token seems too short")
		}

		t.Logf("Received token: %+v", token)

		t.Logf("Successfully received token: %s (expires in %d seconds)",
			token.AccessToken[:10]+"...", token.ExpiresIn)
	})

	t.Run("Context timeout", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 1*time.Millisecond)
		defer cancel()

		_, err := client.GetAccessTokenWithContext(ctx)

		if err == nil {
			t.Error("Expected timeout error, got nil")
		}

		if !strings.Contains(err.Error(), "context deadline exceeded") {
			t.Errorf("Expected context deadline exceeded error, got: %v", err)
		}
	})
}

func TestOAuth2Client_GetAccessToken_With_RegisterClient(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test")
	}

	// Load configuration from environment or use test defaults
	cfg := &config.OAuthClientConfig{
		OAuth2BaseURL:       getTestEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
		DefaultClientID:     getTestEnv("TEST_CLIENT_ID", "test1"),
		DefaultClientSecret: getTestEnv("TEST_CLIENT_SECRET", "test1"),
		DefaultRedirectURL:  getTestEnv("TEST_REDIRECT_URL", "http://127.0.0.1:8083/login/oauth2/code/public-client-react"),
		DefaultScope:        getTestEnv("TEST_SCOPE", "client.create"),
	}

	t.Logf("Config values: %+v", cfg)

	client := NewOAuth2Client(cfg)

	t.Run("Successful token request and client registration", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()

		// Get access token first
		token, err := client.GetAccessTokenWithContext(ctx)

		if err != nil {
			t.Fatalf("Expected no error getting token, got: %v", err)
		}

		if token == nil {
			t.Fatal("Expected token response, got nil")
		}

		// Validate token
		if token.AccessToken == "" {
			t.Error("Expected access_token to be non-empty")
		}

		if token.TokenType == "" {
			t.Error("Expected token_type to be non-empty")
		}

		if token.ExpiresIn <= 0 {
			t.Error("Expected expires_in to be greater than 0")
		}

		t.Logf("Received token: %+v", token)

		// Now test client registration
		clientReq := &model.ClientFormRequest{
			ClientName:              "Test Client Integration",
			RedirectURIs:           []string{"http://localhost:8080/callback", "http://localhost:8080/callback2"},
			GrantTypes:             []string{"authorization_code", "refresh_token"},
			TokenEndpointAuthMethod: "client_secret_basic",
			Description:             "A test client for integration testing",
			Scope:                   "openid profile email",
		}

		clientResp, err := client.RegisterClientWithContext(ctx, token.AccessToken, clientReq)

		if err != nil {
			t.Fatalf("Expected no error registering client, got: %v", err)
		}

		if clientResp == nil {
			t.Fatal("Expected client registration response, got nil")
		}

		// Validate client registration response
		if clientResp.ClientID == "" {
			t.Error("Expected client_id to be non-empty")
		}

		if clientResp.ClientSecret == "" {
			t.Error("Expected client_secret to be non-empty")
		}

		if clientResp.ClientName != clientReq.ClientName {
			t.Errorf("Expected client_name to be %s, got: %s", clientReq.ClientName, clientResp.ClientName)
		}

		if len(clientResp.RedirectURIs) != len(clientReq.RedirectURIs) {
			t.Errorf("Expected %d redirect URIs, got: %d", len(clientReq.RedirectURIs), len(clientResp.RedirectURIs))
		}

		t.Logf("Client Registration Response: %+v", clientResp)
	})

	t.Run("Client registration with invalid token", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		clientReq := &model.ClientFormRequest{
			ClientName:   "Test Client",
			RedirectURIs: []string{"http://localhost:8080/callback"},
			GrantTypes:   []string{"authorization_code"},
		}

		_, err := client.RegisterClientWithContext(ctx, "invalid-token", clientReq)

		if err == nil {
			t.Error("Expected error with invalid token, got nil")
		}

		t.Logf("Got expected error with invalid token: %v", err)
	})

	t.Run("Client registration with invalid request", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		// Get valid token first
		token, err := client.GetAccessTokenWithContext(ctx)
		if err != nil {
			t.Fatalf("Failed to get token: %v", err)
		}

		// Test with invalid client request (missing required fields)
		invalidReq := &model.ClientFormRequest{
			// Missing ClientName and RedirectURIs
			GrantTypes: []string{"authorization_code"},
		}

		_, err = client.RegisterClientWithContext(ctx, token.AccessToken, invalidReq)

		if err == nil {
			t.Error("Expected error with invalid client request, got nil")
		}

		t.Logf("Got expected error with invalid request: %v", err)
	})
}

func TestOAuth2Client_GetAccessToken_InvalidCredentials(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test")
	}

	// Test with invalid credentials
	cfg := &config.OAuthClientConfig{
		OAuth2BaseURL:       getTestEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
		DefaultClientID:     "invalid-client-id",
		DefaultClientSecret: "invalid-client-secret",
		DefaultRedirectURL:  "http://localhost:9088/callback",
		DefaultScope:        "client.create",
	}

	client := NewOAuth2Client(cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	token, err := client.GetAccessTokenWithContext(ctx)

	if err == nil {
		t.Fatal("Expected error for invalid credentials, got nil")
	}

	if token != nil {
		t.Error("Expected nil token on error")
	}

	t.Logf("Got expected error: %v", err)
}

func TestOAuth2Client_GetAccessToken_InvalidServer(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test")
	}

	// Test with invalid server URL
	cfg := &config.OAuthClientConfig{
		OAuth2BaseURL:       "http://invalid-server:9999",
		DefaultClientID:     "test-client",
		DefaultClientSecret: "test-secret",
		DefaultRedirectURL:  "http://localhost:9088/callback",
		DefaultScope:        "client.create",
	}

	client := NewOAuth2Client(cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	token, err := client.GetAccessTokenWithContext(ctx)

	if err == nil {
		t.Fatal("Expected error for invalid server, got nil")
	}

	if token != nil {
		t.Error("Expected nil token on error")
	}

	t.Logf("Got expected error: %v", err)
}

func TestOAuth2Client_ConfigValidation(t *testing.T) {
	tests := []struct {
		name        string
		config      *config.OAuthClientConfig
		expectError bool
	}{
		{
			name:        "nil config",
			config:      nil,
			expectError: true,
		},
		{
			name: "missing base URL",
			config: &config.OAuthClientConfig{
				DefaultClientID:     "test",
				DefaultClientSecret: "test",
				DefaultRedirectURL:  "http://localhost/callback",
			},
			expectError: true,
		},
		{
			name: "missing client ID",
			config: &config.OAuthClientConfig{
				OAuth2BaseURL:       "http://localhost:9088",
				DefaultClientSecret: "test",
				DefaultRedirectURL:  "http://localhost/callback",
			},
			expectError: true,
		},
		{
			name: "missing client secret",
			config: &config.OAuthClientConfig{
				OAuth2BaseURL:      "http://localhost:9088",
				DefaultClientID:    "test",
				DefaultRedirectURL: "http://localhost/callback",
			},
			expectError: true,
		},
		{
			name: "missing redirect URL",
			config: &config.OAuthClientConfig{
				OAuth2BaseURL:       "http://localhost:9088",
				DefaultClientID:     "test",
				DefaultClientSecret: "test",
			},
			expectError: true,
		},
		{
			name: "valid config",
			config: &config.OAuthClientConfig{
				OAuth2BaseURL:       "http://localhost:9088",
				DefaultClientID:     "test",
				DefaultClientSecret: "test",
				DefaultRedirectURL:  "http://localhost/callback",
				DefaultScope:        "test",
			},
			expectError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var client *OAuth2Client
			if tt.config != nil {
				client = NewOAuth2Client(tt.config)
			} else {
				client = &OAuth2Client{config: tt.config}
			}

			err := client.validateConfig()

			if tt.expectError && err == nil {
				t.Error("Expected validation error, got nil")
			}

			if !tt.expectError && err != nil {
				t.Errorf("Expected no validation error, got: %v", err)
			}
		})
	}
}

func getTestEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}