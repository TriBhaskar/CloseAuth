package spring

import (
	"fmt"
	"os"
)

// Config holds all Spring Authorization Server endpoint configuration.
// Loaded once at startup from environment variables.
type Config struct {
	// Base URL of the Spring Authorization Server (e.g., "http://localhost:9088")
	OAuth2ServerURL string

	// Context path appended to base URL (e.g., "/closeauth")
	ContextPath string

	// Client credentials for service-to-service auth (client_credentials grant)
	DefaultClientID     string
	DefaultClientSecret string
	DefaultRedirectURL  string
	DefaultScope        string

	// BFF's own base URL for building redirect URLs
	BFFBaseURL string

	// Environment (controls cookie Secure flag)
	Environment string
}

// LoadConfig loads Spring configuration from environment variables.
func LoadConfig() *Config {
	return &Config{
		OAuth2ServerURL:     getEnv("OAUTH2_SERVER_URL", "http://localhost:9088"),
		ContextPath:         getEnv("OAUTH2_API_CONTEXT_PATH", "/closeauth"),
		DefaultClientID:     getEnv("DEFAULT_CLIENT_ID", "test1"),
		DefaultClientSecret: getEnv("DEFAULT_CLIENT_SECRET", "test1"),
		DefaultRedirectURL:  getEnv("DEFAULT_REDIRECT_URL", "http://127.0.0.1:8083/login/oauth2/code/public-client-react"),
		DefaultScope:        getEnv("DEFAULT_SCOPE", "client.create"),
		BFFBaseURL:          getEnv("BFF_BASE_URL", "http://localhost:8080"),
		Environment:         getEnv("ENVIRONMENT", "development"),
	}
}

// IsProduction returns true if running in production mode.
func (c *Config) IsProduction() bool {
	return c.Environment == "production" || c.Environment == "prod"
}

// baseURL returns the full base URL including context path.
func (c *Config) baseURL() string {
	return fmt.Sprintf("%s%s", c.OAuth2ServerURL, c.ContextPath)
}

// --- OAuth2 Endpoint URLs ---

func (c *Config) TokenURL() string {
	return c.baseURL() + "/oauth2/token"
}

func (c *Config) AuthorizeURL() string {
	return c.baseURL() + "/oauth2/authorize"
}

func (c *Config) LoginURL() string {
	return c.baseURL() + "/login"
}

func (c *Config) ConsentURL() string {
	return c.baseURL() + "/oauth2/consent"
}

func (c *Config) IntrospectURL() string {
	return c.baseURL() + "/oauth2/introspect"
}

func (c *Config) RevocationURL() string {
	return c.baseURL() + "/oauth2/revoke"
}

func (c *Config) JWKSURL() string {
	return c.baseURL() + "/oauth2/jwks"
}

func (c *Config) RegisterClientURL() string {
	return c.baseURL() + "/connect/register"
}

func (c *Config) ClientInfoURL(clientID string) string {
	return c.baseURL() + "/oauth2/client-info?client_id=" + clientID
}

// --- CloseAuth Admin API Endpoints ---

func (c *Config) AdminLoginURL() string {
	return c.baseURL() + "/api/v1/admin/auth/login"
}

func (c *Config) AdminRegisterURL() string {
	return c.baseURL() + "/api/v1/admin/auth/register"
}

func (c *Config) AdminVerifyEmailURL() string {
	return c.baseURL() + "/api/v1/admin/auth/verify-email"
}

func (c *Config) AdminResendOTPURL() string {
	return c.baseURL() + "/api/v1/admin/auth/resend-otp"
}

func (c *Config) AdminForgotPasswordURL() string {
	return c.baseURL() + "/api/v1/admin/auth/forgot-password"
}

func (c *Config) AdminPasswordResetURL() string {
	return c.baseURL() + "/api/v1/admin/auth/reset-password"
}

// --- Helper ---

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
