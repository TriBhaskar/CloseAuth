package server

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"

	"closeauth-frontend/internal/config"
	"closeauth-frontend/internal/proxy"

	_ "github.com/joho/godotenv/autoload"
)

// Server holds all dependencies and serves HTTP requests.
//
// The pre-refactor version of this struct also held a direct database
// connection (*database.Database) — that's gone for good, per the
// "no direct database access, ever" architectural decision (vision §7.8);
// do not reintroduce it.
//
// authProxy (Stage UI-1, Deliverable 4) is Surface 1's pure-relay mechanism —
// see internal/server/handlers_auth_proxy.go for the routes it backs and the
// CSRF reasoning for this specific route pair. internal/backend's OAuthClient/
// AdminClient (Surfaces 2/3, not wired to routes yet) are deliberately NOT
// held here — they hold/interpret credentials, which is a different job than
// the relay this stage wires up.
type Server struct {
	port      int
	logger    *slog.Logger
	authProxy *proxy.Proxy
}

// NewServer constructs the HTTP server.
func NewServer() *http.Server {
	serverCfg := config.LoadServerConfig()
	backendCfg := config.LoadBackendConfig()
	logger := slog.Default()

	s := &Server{
		port:      serverCfg.Port,
		logger:    logger,
		authProxy: proxy.New(backendCfg.BaseURL + backendCfg.ContextPath),
	}

	env := os.Getenv("ENVIRONMENT")
	if env == "" {
		env = "development"
	}

	logger.Info("╔══════════════════════════════════════════════════════╗")
	logger.Info("║              CloseAuth Frontend Server               ║")
	logger.Info("╚══════════════════════════════════════════════════════╝")
	logger.Info(fmt.Sprintf("  → Port          : %d", serverCfg.Port))
	logger.Info(fmt.Sprintf("  → Environment   : %s", env))
	logger.Info(fmt.Sprintf("  → Backend       : %s%s", backendCfg.BaseURL, backendCfg.ContextPath))
	logger.Info(fmt.Sprintf("  → SPA (embed)   : serving Vue dist/ on http://localhost:%d", serverCfg.Port))
	logger.Info(fmt.Sprintf("  → API routes    : http://localhost:%d/api/*", serverCfg.Port))
	logger.Info("──────────────────────────────────────────────────────")
	logger.Info(fmt.Sprintf("Server starting on http://localhost:%d", serverCfg.Port))

	httpServer := &http.Server{
		Addr:         fmt.Sprintf(":%d", serverCfg.Port),
		Handler:      s.RegisterRoutes(),
		IdleTimeout:  serverCfg.IdleTimeout,
		ReadTimeout:  serverCfg.ReadTimeout,
		WriteTimeout: serverCfg.WriteTimeout,
	}

	return httpServer
}
