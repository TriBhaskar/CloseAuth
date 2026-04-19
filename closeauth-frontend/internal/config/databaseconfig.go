package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

type DatabaseConfig struct {
    Host            string
    Port            int
    User            string
    Password        string
    DBName          string
    SSLMode         string
    MaxOpenConns    int
    MaxIdleConns    int
    ConnMaxLifetime time.Duration
}

func LoadDatabaseConfig() (*DatabaseConfig, error) {
    port, err := strconv.Atoi(getEnv("DB_PORT", "5432"))
    if err != nil {
        return nil, fmt.Errorf("invalid DB_PORT: %w", err)
    }

    maxOpen, _ := strconv.Atoi(getEnv("DB_MAX_OPEN_CONNS", "25"))
    maxIdle, _ := strconv.Atoi(getEnv("DB_MAX_IDLE_CONNS", "5"))
    
    lifetime, err := time.ParseDuration(getEnv("DB_CONN_MAX_LIFETIME", "5m"))
    if err != nil {
        lifetime = 5 * time.Minute
    }

    return &DatabaseConfig{
        Host:            getEnv("DB_HOST", "localhost"),
        Port:            port,
        User:            getEnv("DB_USER", "postgres"),
        Password:        getEnv("DB_PASSWORD", ""),
        DBName:          getEnv("DB_NAME", "closeauth_bff"),
        SSLMode:         getEnv("DB_SSLMODE", "disable"),
        MaxOpenConns:    maxOpen,
        MaxIdleConns:    maxIdle,
        ConnMaxLifetime: lifetime,
    }, nil
}

func (c *DatabaseConfig) ConnectionString() string {
    return fmt.Sprintf(
        "host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
        c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode,
    )
}

func getEnv(key, defaultValue string) string {
    if value := os.Getenv(key); value != "" {
        return value
    }
    return defaultValue
}