package client

import (
	"closeauth-backend-for-frontend/internal/config"
	"os"
	"testing"
)

func TestOAuth2Client_GetAccessToken_Integration(t *testing.T){
	// Skip if running unit tests only
    if testing.Short() {
        t.Skip("Skipping integration test")
    }

	// Load configuration from environment or use test defaults
    cfg := &config.OAuthClientConfig{
        OAuth2BaseURL:        getTestEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
        DefaultClientID:      getTestEnv("TEST_CLIENT_ID", "test1"),
        DefaultClientSecret:  getTestEnv("TEST_CLIENT_SECRET", "test1"),
        DefaultRedirectURL:   getTestEnv("TEST_REDIRECT_URL", "http://127.0.0.1:8083/login/oauth2/code/public-client-react"),
        DefaultScope:         getTestEnv("TEST_SCOPE", "client.create"),
    }
    
t.Logf("Config values: %+v", cfg)

    client := NewOAuth2Client(cfg)

	    t.Run("Successful token request", func(t *testing.T) {
        token, err := client.GetAccessToken()
        
        if err != nil {
            t.Fatalf("Expected no error, got: %v", err)
        }
        
        if token == nil {
            t.Fatal("Expected token response, got nil")
        }
        
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
        
        t.Logf("Successfully received token: %s (expires in %d seconds)", 
            token.AccessToken[:10]+"...", token.ExpiresIn)
    })
}

func TestOAuth2Client_GetAccessToken_InvalidCredentials(t *testing.T) {
    if testing.Short() {
        t.Skip("Skipping integration test")
    }

    // Test with invalid credentials
    cfg := &config.OAuthClientConfig{
        OAuth2BaseURL:        getTestEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
        DefaultClientID:      "invalid-client-id",
        DefaultClientSecret:  "invalid-client-secret", 
        DefaultRedirectURL:   "http://localhost:9088/callback",
        DefaultScope:         "client.create",
    }

    client := NewOAuth2Client(cfg)
    
    token, err := client.GetAccessToken()
    
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
        OAuth2BaseURL:        "http://invalid-server:9999",
        DefaultClientID:      "test-client",
        DefaultClientSecret:  "test-secret",
        DefaultRedirectURL:   "http://localhost:9088/callback", 
        DefaultScope:         "client.create",
    }

    client := NewOAuth2Client(cfg)
    
    token, err := client.GetAccessToken()
    
    if err == nil {
        t.Fatal("Expected error for invalid server, got nil")
    }
    
    if token != nil {
        t.Error("Expected nil token on error")
    }
    
    t.Logf("Got expected error: %v", err)
}

func getTestEnv(key, defaultValue string) string {
    if value := os.Getenv(key); value != "" {
        return value
    }
    return defaultValue
}