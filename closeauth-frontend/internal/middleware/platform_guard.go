package middleware

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

type platformSessionContextKey int

const platformSessionKey platformSessionContextKey = iota

// RequirePlatformSession is the platform-admin console's auth gate — the
// cross-tenant analogue of RequireAdminSession, but simpler by construction:
// there is no slug to bind, no tenant-role check (the session's Roles ARE
// platform roles), and — critically — no reauth-trigger branch. The
// platform-admin token is access-only with a 5-minute TTL and no refresh
// (see platform_session.go's doc comment); when it expires, the operator
// signs in again, full stop.
//
// Three outcomes, each mapped to the house JSON error envelope:
//   - no cookie / decrypt failure -> 401 "unauthenticated"
//   - decrypts fine but Roles doesn't include PLATFORM_ADMIN -> 403
//     "not_platform_admin" (a newly created platform admin has no roles by
//     default — creation alone never grants access; see
//     PlatformAdminService.createPlatformAdmin)
//   - decrypts fine, IS a platform admin, but the access token has expired
//     -> 401 "session_expired" with a loginPath — NOT "reauth_required":
//     there is nothing here that silently renews.
func RequirePlatformSession() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			session, err := GetPlatformSession(r)
			if err != nil {
				writePlatformGuardError(w, http.StatusUnauthorized, "unauthenticated",
					"No active platform-admin session.", "")
				return
			}
			if !session.IsPlatformAdmin() {
				writePlatformGuardError(w, http.StatusForbidden, "not_platform_admin",
					"This account does not hold PLATFORM_ADMIN.", "")
				return
			}
			if session.Expired(time.Now()) {
				writePlatformGuardError(w, http.StatusUnauthorized, "session_expired",
					"The platform-admin session has expired. Platform sessions are short-lived "+
						"(5 minutes) and are never silently renewed — sign in again.", "/platform/login")
				return
			}

			ctx := context.WithValue(r.Context(), platformSessionKey, session)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// PlatformSessionFrom retrieves the *PlatformSession stashed by
// RequirePlatformSession. ok is false if called outside a guarded handler.
func PlatformSessionFrom(ctx context.Context) (*PlatformSession, bool) {
	session, ok := ctx.Value(platformSessionKey).(*PlatformSession)
	return session, ok
}

func writePlatformGuardError(w http.ResponseWriter, status int, code, description, loginPath string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	body := map[string]string{"error": code, "error_description": description}
	if loginPath != "" {
		body["loginPath"] = loginPath
	}
	_ = json.NewEncoder(w).Encode(body)
}
