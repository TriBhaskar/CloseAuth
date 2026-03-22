package database

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/jmoiron/sqlx"
	_ "github.com/lib/pq"

	"closeauth-backend-for-frontend/internal/config"
)

type Database struct {
    *sqlx.DB
}

func NewDatabase(cfg *config.DatabaseConfig) (*Database, error) {
    db, err := sqlx.Connect("postgres", cfg.ConnectionString())
    if err != nil {
        return nil, fmt.Errorf("failed to connect to database: %w", err)
    }

    // Set connection pool settings
    db.SetMaxOpenConns(cfg.MaxOpenConns)
    db.SetMaxIdleConns(cfg.MaxIdleConns)
    db.SetConnMaxLifetime(cfg.ConnMaxLifetime)

    // Verify connection
    if err := db.Ping(); err != nil {
        return nil, fmt.Errorf("failed to ping database: %w", err)
    }

    log.Printf("Database connection established: %s:%d/%s", cfg.Host, cfg.Port, cfg.DBName)

    return &Database{db}, nil
}

func (db *Database) Close() error {
    log.Println("Closing database connection...")
    return db.DB.Close()
}

func (db *Database) HealthCheck() error {
    ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
    defer cancel()

    if err := db.PingContext(ctx); err != nil {
        return fmt.Errorf("database health check failed: %w", err)
    }
    return nil
}