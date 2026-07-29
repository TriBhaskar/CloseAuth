package config

import (
	"fmt"
	"time"
)

// BackendConfig holds the location of the real CloseAuth backend the BFF
// proxies to / calls as an admin API client. There is no direct database
// access from the BFF (architectural decision, vision §7.8) — this is the
// only way the BFF reaches backend state.
type BackendConfig struct {
	// BaseURL is the backend's scheme+host+port, e.g. http://localhost:9000.
	// No trailing slash, no context path (ContextPath is applied separately
	// so callers that need to build /actuator/health-style paths without the
	// app's servlet context path still can).
	BaseURL string

	// ContextPath is the backend's servlet context path (server.servlet.context-path
	// in application.yml) — every hand-written and SAS-provided endpoint is
	// served under it. Currently "/closeauth".
	ContextPath string

	// RequestTimeout bounds how long the BFF waits on a single backend call
	// (proxy relay or admin API call) before giving up.
	RequestTimeout time.Duration
}

// LoadBackendConfig loads backend location config from environment variables
// with sensible defaults matching the backend's own docker-profile defaults
// (application-docker.yml: server.port=9000, context-path=/closeauth).
func LoadBackendConfig() *BackendConfig {
	return &BackendConfig{
		BaseURL:        getEnvString("BACKEND_BASE_URL", "http://localhost:9000"),
		ContextPath:    getEnvString("BACKEND_CONTEXT_PATH", "/closeauth"),
		RequestTimeout: getEnvDuration("BACKEND_REQUEST_TIMEOUT", 30*time.Second),
	}
}

// Validate checks if the backend configuration is valid.
func (c *BackendConfig) Validate() error {
	if c.BaseURL == "" {
		return fmt.Errorf("backend base URL must not be empty")
	}
	if c.RequestTimeout <= 0 {
		return fmt.Errorf("backend request timeout must be positive, got %v", c.RequestTimeout)
	}
	return nil
}
