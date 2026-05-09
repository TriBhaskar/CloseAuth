package server

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"

	"closeauth-frontend/internal/config"
	"closeauth-frontend/internal/database"
	"closeauth-frontend/internal/database/repository"
	"closeauth-frontend/internal/spring"

	_ "github.com/joho/godotenv/autoload"
)

// Server holds all dependencies and serves HTTP requests.
type Server struct {
	port         int
	db           *database.Database
	themeRepo    *repository.ThemeRepository
	springClient *spring.SpringClient
	springConfig *spring.Config
	logger       *slog.Logger
}

func NewServer() *http.Server {
	// Load server config from environment
	serverCfg := config.LoadServerConfig()

	logger := slog.Default()

	// Load Spring config
	springCfg := spring.LoadConfig()

	// Initialize token manager and Spring client
	tokenManager := spring.NewTokenManager(logger)
	springClient := spring.NewSpringClient(springCfg, tokenManager, logger)

	// Initialize database (optional — graceful degradation if DB not available)
	dbCfg, dbCfgErr := config.LoadDatabaseConfig()
	var db *database.Database
	var themeRepo *repository.ThemeRepository

	if dbCfgErr == nil {
		var err error
		db, err = database.NewDatabase(dbCfg)
		if err != nil {
			logger.Warn("database connection failed, theme features disabled", "error", err)
		} else {
			themeRepo = repository.NewThemeRepository(db)
		}
	} else {
		logger.Warn("database config not available, theme features disabled", "error", dbCfgErr)
	}

	s := &Server{
		port:         serverCfg.Port,
		db:           db,
		themeRepo:    themeRepo,
		springClient: springClient,
		springConfig: springCfg,
		logger:       logger,
	}

	// ── Startup banner ──────────────────────────────────────────────────────
	env := os.Getenv("ENVIRONMENT")
	if env == "" {
		env = "development"
	}

	logger.Info("╔══════════════════════════════════════════════════════╗")
	logger.Info("║              CloseAuth Frontend Server              ║")
	logger.Info("╚══════════════════════════════════════════════════════╝")
	logger.Info(fmt.Sprintf("  → Port          : %d", serverCfg.Port))
	logger.Info(fmt.Sprintf("  → Environment   : %s", env))
	logger.Info(fmt.Sprintf("  → Spring Server : %s", springCfg.OAuth2ServerURL))

	if db != nil {
		logger.Info("  → Database      : connected ✓")
	} else {
		logger.Warn("  → Database      : disconnected (theme features disabled)")
	}

	logger.Info(fmt.Sprintf("  → SPA (embed)   : serving Vue dist/ on http://localhost:%d", serverCfg.Port))
	logger.Info(fmt.Sprintf("  → API routes    : http://localhost:%d/api/*", serverCfg.Port))
	logger.Info(fmt.Sprintf("  → OAuth proxy   : http://localhost:%d/closeauth/oauth2/*", serverCfg.Port))
	logger.Info("──────────────────────────────────────────────────────")
	logger.Info(fmt.Sprintf("Server starting on http://localhost:%d", serverCfg.Port))

	server := &http.Server{
		Addr:         fmt.Sprintf(":%d", serverCfg.Port),
		Handler:      s.RegisterRoutes(),
		IdleTimeout:  serverCfg.IdleTimeout,
		ReadTimeout:  serverCfg.ReadTimeout,
		WriteTimeout: serverCfg.WriteTimeout,
	}

	return server
}

// HealthCheck checks database health.
func (s *Server) HealthCheck() error {
	if s.db == nil {
		return fmt.Errorf("database not connected")
	}
	return s.db.HealthCheck()
}
