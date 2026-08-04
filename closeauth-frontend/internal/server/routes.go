package server

import (
	"encoding/json"
	"net/http"
	"time"

	"closeauth-frontend/internal/static"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

// RegisterRoutes wires up the chi router.
//
// Stage UI-0 left this as a minimal skeleton (the pre-refactor proxy/auth/
// admin handlers were deleted against a stale backend contract — see
// CLOSEAUTH_FRONTEND_BFF_SNAPSHOT.md / UI_STAGE_0_REPORT.md). Stage UI-1
// reintroduces the FIRST real piece of Surface 1: a login/logout proxy pair
// (handlers_auth_proxy.go) built on the generic relay in internal/proxy.
// Deliberately no CSRF middleware wraps this pair — see
// handlers_auth_proxy.go's doc comment for the reasoning.
//
// Stage UI-2a adds two more pieces of Surface 1 on the SAME no-CSRF, no-
// BFF-session pattern: a pure-relay GET /branding (handlers_branding_proxy.go)
// and — genuinely new, not just "more of the same pattern" — the JSON/fetch
// login translation mode at POST /api/auth/login (handlers_login_json.go),
// which exists alongside POST /login (unchanged) rather than replacing it;
// see that file's doc comment for why a distinct route was chosen over an
// Accept-header switch on /login itself.
//
// Stage UI-2b adds registration, email verification, and invite acceptance —
// three more pure-relay routes (handlers_registration_proxy.go), same
// pattern, still no translation needed (none of them ever redirect either).
//
// Stage UI-2c-i adds magic-link's request step and password reset's request
// + confirm steps — same pure-relay pattern, still no translation needed
// (none of the three ever redirect either). Magic-link's GET
// /magic-link/consume deliberately has NO route here — it's a real browser
// navigation straight to the backend's own origin, never through the BFF
// (Design Decision #1, CLOSEAUTH_UI_STAGE_2c-i_PROMPT.md).
//
// Stage UI-2c-ii adds consent's context-fetch proxy, GET /oauth2/consent
// (handlers_consent_proxy.go) — NOT a pure relay like its siblings above: it
// decodes the backend's ConsentContext JSON, injects an authorizeUrl field
// (the backend's real, absolute /oauth2/authorize — computed from
// BackendConfig, never a new backend field), and re-encodes before returning
// it. The consent DECISION (approve/deny) has no route here at all, and never
// will — it's a genuine native HTML form POST straight to the backend's own
// /oauth2/authorize, exactly like magic-link's consume step, for the same
// cross-origin-cookie reason (see CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md).
// TODO(ui-3): wire Surfaces 2/3 (admin console) routes once internal/backend's
// OAuthClient/AdminClient have somewhere to hold their Session (needs Option
// A's backend piece — the deterministic per-tenant admin-console client —
// tracked but explicitly out of scope through UI-1/UI-2).
func (s *Server) RegisterRoutes() http.Handler {
	r := chi.NewRouter()
	r.Use(chimw.Logger)
	r.Use(chimw.Recoverer)

	// CORS — allow Vue dev server and same-origin in production.
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// ──────────────────────────────────────────────────────────────────────────
	// Public API routes
	// ──────────────────────────────────────────────────────────────────────────
	r.Get("/api/health", s.handleHealthCheck)

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 1 — hosted end-user auth pages: pure relay to the real backend,
	// no BFF-side session/CSRF state (see handlers_auth_proxy.go).
	// ──────────────────────────────────────────────────────────────────────────
	r.Post("/login", s.handleLoginProxy)
	r.Post("/logout", s.handleLogoutProxy)
	r.Get("/branding", s.handleBrandingProxy)

	// Stage UI-2b, Deliverable 1: registration, verification — see
	// handlers_registration_proxy.go.
	r.Post("/register", s.handleRegisterProxy)
	r.Post("/verify-email/request", s.handleVerifyEmailRequestProxy)
	r.Post("/verify-email/confirm", s.handleVerifyEmailConfirmProxy)

	// Stage UI-2c-i, Deliverable 1: magic-link's request step (see
	// handlers_magic_link_proxy.go — the consume step deliberately has no
	// route here, Design Decision #1) and password reset's request + confirm
	// steps (handlers_password_reset_proxy.go).
	r.Post("/magic-link/request", s.handleMagicLinkRequestProxy)
	r.Post("/password-reset/request", s.handlePasswordResetRequestProxy)
	r.Post("/password-reset/confirm", s.handlePasswordResetConfirmProxy)

	// Stage UI-2c-ii, Deliverable 1: consent's context-fetch proxy only (see
	// handlers_consent_proxy.go) — the decision-submission is deliberately NOT
	// wired here at all; see that file's doc comment.
	r.Get("/oauth2/consent", s.handleConsentContextProxy)

	// ──────────────────────────────────────────────────────────────────────────
	// Surface 1 — JSON/fetch login mode (Stage UI-2a, Deliverable 1): the
	// redirect-vs-JSON-error translation. Distinct from POST /login above —
	// see handlers_login_json.go's doc comment.
	// ──────────────────────────────────────────────────────────────────────────
	r.Post("/api/auth/login", s.handleLoginJSON)

	// ──────────────────────────────────────────────────────────────────────────
	// SPA catch-all — serve embedded Vue dist/ (fallback to index.html)
	// ──────────────────────────────────────────────────────────────────────────
	r.NotFound(static.SPAHandler().ServeHTTP)

	return r
}

// handleHealthCheck reports basic liveness. It no longer reports database
// health — the BFF has no direct database connection (per the "no direct
// database access, ever" architectural decision).
func (s *Server) handleHealthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]any{
		"status":    "ok",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
}
