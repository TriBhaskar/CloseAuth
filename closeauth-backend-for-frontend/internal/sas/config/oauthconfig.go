package config

import "os"

type OAuthClientConfig struct {
	ServerAddr          string
	OAuth2BaseURL       string
	DefaultClientID     string
	DefaultClientSecret string
	DefaultRedirectURL  string
	DefaultScope        string
}

func LoadOAuthClientConfig() *OAuthClientConfig {
	return &OAuthClientConfig{
		ServerAddr:          getEnv("SERVER_ADDR", ":8080"),
		OAuth2BaseURL:       getEnv("OAUTH2_BASE_URL", "http://localhost:9088"),
		DefaultClientID:     getEnv("DEFAULT_CLIENT_ID", "test1"),
		DefaultClientSecret: getEnv("DEFAULT_CLIENT_SECRET", "test1"),
		DefaultRedirectURL:  getEnv("DEFAULT_REDIRECT_URL", "http://127.0.0.1:8083/login/oauth2/code/public-client-react"),
		DefaultScope:        getEnv("DEFAULT_SCOPE", "client.create"),
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
