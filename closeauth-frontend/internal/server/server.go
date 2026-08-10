package server

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/config"
	"closeauth-frontend/internal/middleware"
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
// CSRF reasoning for this specific route pair.
//
// Stage UI-3a: the BFF becomes a REAL OAuth2 client here (unlike Surface 1's
// pure relay above) for the tenant-admin console — it drives PKCE and holds
// tokens server-side in an encrypted cookie; no access token ever reaches
// browser JS. bff/oauthClient/adminClient back that surface; the doc comment
// that used to say these were "deliberately NOT held here yet" no longer
// applies.
type Server struct {
	port      int
	logger    *slog.Logger
	authProxy *proxy.Proxy

	// authorizeURL is the backend's own real, absolute /oauth2/authorize
	// endpoint (Stage UI-2c-ii, consent). Computed once from BackendConfig —
	// the same config every other proxy route already targets — and injected
	// into the consent context-fetch proxy's response (handlers_consent_proxy.go)
	// as the `authorizeUrl` field ConsentView.vue's native form `action` points
	// at. NEVER used to reach the backend on the BFF's own behalf (that would
	// defeat the whole point — the decision-submission is a genuine top-level
	// browser navigation straight to the backend's origin, no BFF hop).
	authorizeURL string

	// bff, oauthClient, adminClient back the Stage UI-3a tenant-admin console
	// surface (handlers_admin_*.go). All three are pointers rather than
	// values specifically so a zero-value/partial *Server — the pattern every
	// pre-existing *_test.go file in this package uses (e.g.
	// &Server{authProxy: proxy.New(...)}) — compiles and runs RegisterRoutes()
	// without a nil-pointer panic; see bffConfig() and the admin handlers'
	// own nil checks, the structural analogue of the handlers' existing
	// slog.Default()-not-s.logger rule (login_json_test.go exercises a
	// *Server with a nil logger for the same reason).
	bff         *config.BFFConfig
	oauthClient *backend.OAuthClient
	adminClient *backend.AdminClient
}

// bffConfig returns s.bff, or a fresh default BFFConfig when s.bff is nil.
// Every existing *_test.go in this package constructs a partial *Server
// (e.g. &Server{authProxy: proxy.New(...)}) and calls RegisterRoutes()
// directly — this accessor is what lets that keep working after Stage
// UI-3a's route group is added, exactly like handlers using slog.Default()
// instead of s.logger for the same reason.
func (s *Server) bffConfig() *config.BFFConfig {
	if s.bff != nil {
		return s.bff
	}
	return config.LoadBFFConfig()
}

// NewServer constructs the HTTP server.
func NewServer() *http.Server {
	serverCfg := config.LoadServerConfig()
	backendCfg := config.LoadBackendConfig()
	bffCfg := config.LoadBFFConfig()
	logger := slog.Default()

	if err := bffCfg.Validate(); err != nil {
		logger.Error(fmt.Sprintf("invalid BFF admin-console configuration: %v", err))
	}
	// This mechanism (oauthContextTTL, in internal/middleware/oauth_context.go)
	// predates Stage UI-3a; this is its first actual caller.
	middleware.SetOAuthContextTTL(int(bffCfg.OAuthContextTTL.Seconds()))

	s := &Server{
		port:         serverCfg.Port,
		logger:       logger,
		authProxy:    proxy.New(backendCfg.BaseURL + backendCfg.ContextPath),
		authorizeURL: backendCfg.BaseURL + backendCfg.ContextPath + "/oauth2/authorize",
		bff:          bffCfg,
		oauthClient:  backend.NewOAuthClient(backendCfg.BaseURL, backendCfg.ContextPath, bffCfg.AdminCallbackURL()),
		adminClient:  backend.NewAdminClient(backendCfg.BaseURL, backendCfg.ContextPath),
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
	// Stage UI-3a: this MUST byte-match the backend's closeauth.bff.admin-callback
	// (docker-profile env BFF_ADMIN_CALLBACK) or every admin-console token
	// exchange fails with an opaque invalid_grant — the single most likely
	// misconfiguration in this stage, so it gets its own explicit log line.
	logger.Info(fmt.Sprintf("  → Admin callback: %s (must match the backend's closeauth.bff.admin-callback)", bffCfg.AdminCallbackURL()))
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
