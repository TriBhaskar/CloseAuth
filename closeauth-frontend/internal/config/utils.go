package config

import (
	"os"
	"strconv"
	"time"
)

// getEnvDuration retrieves a duration from environment variable or returns default.
// Supports both duration strings (e.g., "1m30s") and integer seconds.
func getEnvDuration(key string, defaultValue time.Duration) time.Duration {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}

	if d, err := time.ParseDuration(value); err == nil {
		return d
	}

	if seconds, err := strconv.Atoi(value); err == nil {
		return time.Duration(seconds) * time.Second
	}

	return defaultValue
}

// getEnvInt retrieves an integer from environment variable or returns default.
func getEnvInt(key string, defaultValue int) int {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}

	if intValue, err := strconv.Atoi(value); err == nil {
		return intValue
	}

	return defaultValue
}
