package server

import "net/http"

// Surface 1's first real route pair (Stage UI-1, Deliverable 4): a login
// proxy and a logout proxy, relaying to the backend's POST /login and
// POST /logout exactly as-is (method, form body, cookies in; status, body,
// Set-Cookie headers out — see internal/proxy). Registered in routes.go.
// UI-2 builds the rest of Surface 1 (registration, verification, magic-link,
// reset, consent) on this same pattern.
//
// # CSRF decision for this route pair: NONE applied, deliberately
//
// The attack class a CSRF token on /login would nominally guard against is
// login CSRF / session fixation: an attacker crafts a cross-site form that
// submits the ATTACKER's own credentials to the victim's browser, silently
// logging the victim in as the attacker, so the victim may then unknowingly
// enter sensitive data (e.g. into a "connected account") under the
// attacker's identity rather than their own. Named explicitly and verified
// against the CURRENT backend (not assumed from the pre-refactor code's
// softer "attacker needs the victim's credentials anyway" framing, which
// undersold this), the decision to apply no CSRF token still holds, for two
// independent, backend-confirmed reasons plus one supporting one:
//
//  1. Session fixation is closed at the backend, unconditionally.
//     AuthServerSessionService.createSession (closeauth-backend
//     session/service/AuthServerSessionService.java) always mints a brand
//     new, cryptographically random session key on every successful
//     authentication — it takes no existing session/cookie as input and
//     never reads, reuses, or elevates whatever was already present on the
//     request. LoginController and MagicLinkController both call it
//     unconditionally via LoginSuccessResponder.establishSessionAndResolve-
//     Redirect on every successful login. So a forged /login can log the
//     victim's browser into the attacker's account, but it can never cause
//     the victim's own subsequent successful login to be silently fused
//     onto a session the attacker already controls — there's nothing to
//     fixate onto.
//  2. The remaining harm is a shared responsibility with the OAuth2 RP, not
//     something a CSRF token on /login alone would fully close even if one
//     existed. This login page is only ever reached via an RP-initiated
//     redirect to /oauth2/authorize; a spec-compliant relying party
//     generates and verifies the OAuth2 `state` parameter round-trips
//     unchanged (RFC 6749 §10.12). That check is what actually stops the
//     "victim unknowingly acts under the attacker's identity" harm from
//     completing the flow back at the RP — a CSRF token scoped to this BFF's
//     /login route can't see or influence the RP's own state validation, so
//     it wouldn't close this half of the risk regardless.
//  3. Chicken-and-egg for /login specifically (supporting point, not the
//     primary justification): CSRF protection needs a token minted and
//     handed to the browser BEFORE the protected POST, tied to a session
//     that already exists. There is no BFF-side session (or CSRF cookie) at
//     the point a visitor POSTs /login — by design, Surface 1 carries NO
//     BFF-side session state at all (this stage's Session type is for
//     Surfaces 2/3 only; see internal/backend/session.go). A forged /logout
//     just logs the victim out — a nuisance, not a confidentiality/integrity
//     compromise; the backend's /logout is explicitly idempotent
//     (API_REFERENCE.md §1) and designed to be safe to call speculatively.
//
// This does NOT generalize to Surfaces 2/3: once a route acts on an
// established, stateful admin-console session with real consequences
// (changing tenant config, revoking a role, etc.), CSRF protection is
// warranted again — internal/middleware's CSRFTokenMiddleware/
// CSRFValidationMiddleware are kept for exactly that (unwired until a later
// stage adds those routes), not deleted.
func (s *Server) handleLoginProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/login")
}

func (s *Server) handleLogoutProxy(w http.ResponseWriter, r *http.Request) {
	s.authProxy.ServeTo(w, r, "/logout")
}
