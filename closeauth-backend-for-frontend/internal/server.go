package server

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"closeauth-backend-for-frontend/internal/config"
	"closeauth-backend-for-frontend/internal/database"
	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/handlers"

	_ "github.com/joho/godotenv/autoload"
)

type Server struct {
    port                   int
    authHandler            *handlers.AuthHandler
    clientHandler          *handlers.ClientHandler
    publicHandler          *handlers.PublicHandler
    oauthProxyHandler      *handlers.OAuthProxyHandler
    oauthClientAuthHandler *handlers.OAuthClientAuthHandler
    db                     *database.Database
}

func NewServer() (*http.Server, *Server, error) {
    port, _ := strconv.Atoi(os.Getenv("PORT"))
    
    // Load database configuration with error handling
    dbConfig, err := config.LoadDatabaseConfig()
    if err != nil {
        return nil, nil, fmt.Errorf("failed to load database config: %w", err)
    }

    // Initialize database connection with error handling
    db, err := database.NewDatabase(dbConfig)
    if err != nil {
        return nil, nil, fmt.Errorf("failed to initialize database: %w", err)
    }

    // Initialize theme repository for client-specific themes
    themeRepo := repository.NewThemeRepository(db)

    // Create server instance
    newServer := &Server{
        port:                   port,
        authHandler:            handlers.NewAuthHandler(),
        clientHandler:          handlers.NewClientHandler(),
        publicHandler:          handlers.NewPublicHandler(),
        oauthProxyHandler:      handlers.NewOAuthProxyHandler(),
        oauthClientAuthHandler: handlers.NewOAuthClientAuthHandler(themeRepo),
        db:                     db,
    }
    
    log.Printf("Starting server on port %d\n", newServer.port)

    // Declare Server config
    httpServer := &http.Server{
        Addr:         fmt.Sprintf(":%d", newServer.port),
        Handler:      newServer.RegisterRoutes(),
        IdleTimeout:  time.Minute,
        ReadTimeout:  10 * time.Second,
        WriteTimeout: 30 * time.Second,
    }
    
    log.Printf("Server configured on port %d\n", newServer.port)

    return httpServer, newServer, nil
}

// Shutdown gracefully closes database connections
func (s *Server) Shutdown(ctx context.Context) error {
    log.Println("Shutting down server resources...")
    
    if s.db != nil {
        if err := s.db.Close(); err != nil {
            log.Printf("Error closing database connection: %v", err)
            return err
        }
    }
    
    log.Println("Server resources cleaned up successfully")
    return nil
}

// HealthCheck returns database health status
func (s *Server) HealthCheck() error {
    if s.db == nil {
        return fmt.Errorf("database not initialized")
    }
    return s.db.HealthCheck()
}