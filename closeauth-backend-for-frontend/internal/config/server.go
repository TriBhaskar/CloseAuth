package config

import (
	"fmt"
	"time"
)

// ServerConfig holds HTTP server configuration settings
type ServerConfig struct {
	// IdleTimeout is the maximum amount of time to wait for the next request when keep-alives are enabled
	IdleTimeout time.Duration

	// ReadTimeout is the maximum duration for reading the entire request, including the body
	ReadTimeout time.Duration

	// WriteTimeout is the maximum duration before timing out writes of the response
	WriteTimeout time.Duration

	// Port is the server port number
	Port int
}

// LoadServerConfig loads server configuration from environment variables with sensible defaults
func LoadServerConfig() *ServerConfig {
	return &ServerConfig{
		IdleTimeout:  getEnvDuration("SERVER_IDLE_TIMEOUT", time.Minute),
		ReadTimeout:  getEnvDuration("SERVER_READ_TIMEOUT", 10*time.Second),
		WriteTimeout: getEnvDuration("SERVER_WRITE_TIMEOUT", 30*time.Second),
		Port:         getEnvInt("PORT", 8080),
	}
}

// Validate checks if the server configuration is valid
func (c *ServerConfig) Validate() error {
	if c.IdleTimeout < 0 {
		return fmt.Errorf("idle timeout must be non-negative, got %v", c.IdleTimeout)
	}
	if c.ReadTimeout <= 0 {
		return fmt.Errorf("read timeout must be positive, got %v", c.ReadTimeout)
	}
	if c.WriteTimeout <= 0 {
		return fmt.Errorf("write timeout must be positive, got %v", c.WriteTimeout)
	}
	if c.Port <= 0 || c.Port > 65535 {
		return fmt.Errorf("port must be between 1 and 65535, got %d", c.Port)
	}
	return nil
}
