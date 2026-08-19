package server

import (
	"net/http"
	"strings"
)

// FE-2a (spec §6.1): a thin pure-relay addition for GET /api/entry/resolve —
// wired exactly like handlers_branding_proxy.go's GET /branding (no
// translation needed; the backend's response is already the exact shape the
// SPA wants). Public/unauthenticated on the backend by construction (the
// tenant isn't known yet), so this carries no BFF-side session/CSRF state.
//
// What IS BFF-specific: routes.go wraps this route in a per-IP rate limiter
// (internal/middleware/ratelimit.go) before it ever reaches this handler —
// spec §6.1's own words: "the BFF must rate-limit it per IP." The backend
// itself deliberately has no rate limit on /entry/resolve (see
// EntryController's own doc comment) — it only ever sees this BFF's IP, not
// the real caller's, since it sits behind this proxy.
//
// Spec §6.1's Data line, verbatim: "The frontend sends the canonical,
// normalised form; the BFF re-normalises rather than trusting it." The SPA's
// entry field already normalises before ever calling this endpoint, but
// /t/{tenantId} (TenantResolverView.vue) can be reached directly — a
// bookmark, a shared link, a hand-typed URL — with a non-canonical value
// (wrong case, missing ten_ prefix), so this handler re-normalises the
// incoming tenantId itself before relaying, rather than trusting the
// caller. normalizeTenantID never REJECTS a shape — EntryController.java's
// own strict regex match is what actually rejects; this only maximises the
// chance a legitimately-meant value reaches that check in canonical form.
func (s *Server) handleEntryResolveProxy(w http.ResponseWriter, r *http.Request) {
	if tenantID := r.URL.Query().Get("tenantId"); tenantID != "" {
		q := r.URL.Query()
		q.Set("tenantId", normalizeTenantID(tenantID))
		r.URL.RawQuery = q.Encode()
	}
	s.authProxy.ServeTo(w, r, "/entry/resolve")
}

// normalizeTenantID mirrors the SPA's own entry-field normalisation (spec
// §1.2: "forgiving on input, strict on display") — lowercase, trim, reduce a
// pasted URL down to the path segment following "/t/", and prefix "ten_" if
// missing.
func normalizeTenantID(raw string) string {
	value := strings.ToLower(strings.TrimSpace(raw))
	if idx := strings.Index(value, "/t/"); idx != -1 {
		rest := value[idx+len("/t/"):]
		if cut := strings.IndexAny(rest, "/?#"); cut != -1 {
			rest = rest[:cut]
		}
		value = rest
	}
	if value != "" && !strings.HasPrefix(value, "ten_") {
		value = "ten_" + value
	}
	return value
}
