package server

import (
	"encoding/json"
	"net/http"

	"closeauth-frontend/internal/proxy"
)

// Stage UI-2c-ii (consent), Deliverable 1 (Go side): a thin proxy for the
// context-fetch ONLY — GET /oauth2/consent — mirroring handlers_branding_proxy.go's
// pure-relay pattern with exactly one addition, because this route is NOT a
// pure byte-for-byte relay like branding/registration/magic-link-request/
// password-reset-request: the backend's real ConsentContext response
// (confirmed against auth/dto/ConsentContext.java — exactly 5 fields:
// clientId, clientName, state, scopes, alreadyGranted; NO backend-base-URL
// field, and none was added) is decoded, augmented with one extra field this
// proxy computes itself — authorizeUrl, the backend's real, absolute
// /oauth2/authorize endpoint — and re-encoded before being returned to Vue.
// s.authorizeURL is built once in NewServer from the SAME BackendConfig every
// other proxy route already targets, so this needs zero new backend surface.
//
// This is the ONLY thing this route does. The consent DECISION (approve/deny)
// is never routed through here, or through the BFF at all — it's a genuine
// native HTML <form method="post" action="{authorizeUrl}"> submitted as a
// real top-level browser navigation straight to the backend's own origin (see
// ConsentView.vue). Building that as a fetch()/JSON call through this proxy
// (even one that superficially resembled handlers_login_json.go's pattern)
// would silently reintroduce the exact cross-origin cookie problem the whole
// design avoids — login needed a JSON-envelope translation specifically
// because it had a genuine inline-error case to handle; consent has none
// (both approve and deny always end in a redirect), so there's nothing here
// for a fetch()-based mechanism to correctly do that a plain form doesn't
// already do better. See CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md §1 Q4.
//
// Documented, accepted degradation (design doc §2): alreadyGranted always
// comes back empty through this proxy, since ConsentController's cosmetic
// pre-highlight lookup depends on the CLOSEAUTH_SESSION cookie, which never
// reaches the BFF for this cross-origin GET. This is a deliberate, reasoned
// trade-off, not a bug — no client-side caching/localStorage workaround is
// added to fake persistence.
func (s *Server) handleConsentContextProxy(w http.ResponseWriter, r *http.Request) {
	result, err := s.authProxy.Relay(r, "/oauth2/consent")
	if err != nil {
		http.Error(w, `{"error":"bad_gateway"}`, http.StatusBadGateway)
		return
	}

	proxy.CopyHeaders(w.Header(), result.Header)

	// Only a 200 carries a real ConsentContext body to augment; the backend's
	// one documented error case (an unresolvable client_id) is a bare 400
	// with no JSON body (ConsentController.consent's `ResponseEntity.badRequest().build()`)
	// — relay it completely unchanged, same discipline as every sibling proxy.
	if result.StatusCode != http.StatusOK {
		w.WriteHeader(result.StatusCode)
		_, _ = w.Write(result.Body)
		return
	}

	var context map[string]any
	if err := json.Unmarshal(result.Body, &context); err != nil {
		http.Error(w, `{"error":"bad_gateway"}`, http.StatusBadGateway)
		return
	}
	context["authorizeUrl"] = s.authorizeURL

	encoded, err := json.Marshal(context)
	if err != nil {
		http.Error(w, `{"error":"bad_gateway"}`, http.StatusBadGateway)
		return
	}

	// The backend's own Content-Length (already copied above by CopyHeaders)
	// is now stale — the augmented body is a different length than the
	// backend's original; a stale header would make net/http silently
	// truncate/misinterpret the real, longer response, exactly the same
	// pitfall handlers_login_json.go's redirect-translation branch documents.
	w.Header().Del("Content-Length")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(encoded)
}
