package config

import (
	"fmt"
	"time"
)

// MiddlewareConfig holds middleware configuration settings
type MiddlewareConfig struct {
	// OAuthContextCookieMaxAge is the maximum age of OAuth context cookie in seconds
	OAuthContextCookieMaxAge int

	// SessionCookieMaxAge is the maximum age of session cookie in seconds
	// Set to 0 for session cookies (expires when browser closes)
	SessionCookieMaxAge int

	// CSRFTokenLength is the length of CSRF tokens in bytes
	CSRFTokenLength int

	// SessionTimeout is the duration before a session expires
	SessionTimeout time.Duration
}

// LoadMiddlewareConfig loads middleware configuration from environment variables with sensible defaults
func LoadMiddlewareConfig() *MiddlewareConfig {
	return &MiddlewareConfig{
		OAuthContextCookieMaxAge: getEnvInt("OAUTH_CONTEXT_COOKIE_MAX_AGE", 600), // 10 minutes
		SessionCookieMaxAge:      getEnvInt("SESSION_COOKIE_MAX_AGE", 0),         // Session cookie (browser close)
		CSRFTokenLength:          getEnvInt("CSRF_TOKEN_LENGTH", 32),
		SessionTimeout:           getEnvDuration("SESSION_TIMEOUT", 24*time.Hour),
	}
}

// Validate checks if the middleware configuration is valid
func (c *MiddlewareConfig) Validate() error {
	if c.OAuthContextCookieMaxAge < 60 {
		return fmt.Errorf("OAuth context cookie max age must be at least 60 seconds, got %d", c.OAuthContextCookieMaxAge)
	}
	if c.OAuthContextCookieMaxAge > 3600 {
		return fmt.Errorf("OAuth context cookie max age should not exceed 3600 seconds (1 hour), got %d", c.OAuthContextCookieMaxAge)
	}
	if c.SessionCookieMaxAge < 0 {
		return fmt.Errorf("session cookie max age must be non-negative, got %d", c.SessionCookieMaxAge)
	}
	if c.CSRFTokenLength < 16 {
		return fmt.Errorf("CSRF token length must be at least 16 bytes, got %d", c.CSRFTokenLength)
	}
	if c.SessionTimeout < time.Minute {
		return fmt.Errorf("session timeout must be at least 1 minute, got %v", c.SessionTimeout)
	}
	return nil
}
