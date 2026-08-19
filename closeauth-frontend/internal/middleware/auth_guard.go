package middleware

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

// SlugFunc extracts the request's tenant slug (from the URL path). Injected
// by the caller (internal/server/routes.go, backed by chi.URLParam) so this
// package never needs to import chi directly.
type SlugFunc func(*http.Request) string

type contextKey int

const adminSessionContextKey contextKey = iota

// RequireAdminSession is the tenant-admin console's auth gate. It replaces
// the old RequireAuth, fixing its one real flaw (it validated the session
// then discarded it, forcing every downstream handler to decrypt the cookie
// a second time) by stashing the decrypted *AdminSession in the request
// context (see AdminSessionFrom) on success.
//
// Three distinct outcomes, each mapped to the house JSON error envelope:
//   - no cookie / decrypt failure / slug mismatch -> 401 "unauthenticated"
//   - decrypts fine but tenant_roles doesn't include TENANT_ADMIN
//     -> 403 "not_tenant_admin" (authorization ≠ authentication: a
//     genuinely authenticated non-admin gets a clear, non-looping refusal,
//     never a silent 401 that looks like "not logged in")
//   - a TENANT_ADMIN session whose access token is expired or within skew
//     of expiring -> 401 "reauth_required" plus a reauthPath the SPA
//     navigates to — the ONLY re-auth trigger in this codebase; there is no
//     timer anywhere, so nothing interrupts a user mid-form.
func RequireAdminSession(slug SlugFunc, skew time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			s := slug(r)

			session, err := GetAdminSession(r, s)
			if err != nil {
				writeGuardError(w, http.StatusUnauthorized, "unauthenticated",
					"No active tenant-admin session for this tenant.", "")
				return
			}
			if !session.IsTenantAdmin() {
				writeGuardError(w, http.StatusForbidden, "not_tenant_admin",
					"This account does not have TENANT_ADMIN access to this tenant.", "")
				return
			}
			if session.NeedsReauth(time.Now(), skew) {
				writeGuardError(w, http.StatusUnauthorized, "reauth_required",
					"The session's access token is expired or near expiry.", "/t/"+s+"/admin/reauth")
				return
			}

			ctx := context.WithValue(r.Context(), adminSessionContextKey, session)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// AdminSessionFrom retrieves the *AdminSession stashed by RequireAdminSession
// OR RequireTenantSession (both use the same context key — a session is a
// session; only the GATE differs).
// ok is false if called outside either guard's handler tree.
func AdminSessionFrom(ctx context.Context) (*AdminSession, bool) {
	session, ok := ctx.Value(adminSessionContextKey).(*AdminSession)
	return session, ok
}

// RequireTenantSession is FE-4d's self-service gate: any valid, non-expired
// session for this tenant — admin or not. Identical to RequireAdminSession
// minus the IsTenantAdmin() check, since /v1/me/** (its only consumer) is
// deliberately open to every tenant user by spec (§6.4.8). Existing
// admin-CRUD routes stay exactly as gated as before — this is an ADDITIONAL,
// weaker gate for a NEW, separate route group, never a replacement.
func RequireTenantSession(slug SlugFunc, skew time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			s := slug(r)

			session, err := GetAdminSession(r, s)
			if err != nil {
				writeGuardError(w, http.StatusUnauthorized, "unauthenticated",
					"No active session for this tenant.", "")
				return
			}
			if session.NeedsReauth(time.Now(), skew) {
				writeGuardError(w, http.StatusUnauthorized, "reauth_required",
					"The session's access token is expired or near expiry.", "/t/"+s+"/admin/reauth")
				return
			}

			ctx := context.WithValue(r.Context(), adminSessionContextKey, session)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func writeGuardError(w http.ResponseWriter, status int, code, description, reauthPath string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	body := map[string]string{"error": code, "error_description": description}
	if reauthPath != "" {
		body["reauthPath"] = reauthPath
	}
	_ = json.NewEncoder(w).Encode(body)
}
