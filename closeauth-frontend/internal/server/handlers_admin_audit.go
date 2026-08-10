package server

import (
	"net/http"
	"net/url"

	"github.com/go-chi/chi/v5"
)

// Stage UI-3e: the read-only audit query surface — one handler, no
// mutations, so no CSRF-relevant code here at all (the /t/{slug}/api
// subrouter's CSRFValidationMiddleware only checks non-GET methods anyway).

// auditFilterParams allow-lists TenantAuditController's six documented
// filters by their exact snake_case query-param names (AuditQueryParams.java
// / API_REFERENCE.md §6) — event_type, from, to, user_id, client_id, actor.
// Unlike pagingQuery, values are forwarded RAW, with no BFF-side
// UUID/instant/enum validation: these are query values the backend already
// validates (AuditQueryParams turns a bad one into a clean 400
// audit.invalid_<field>), and duplicating that validation here would just
// invent a second, differently-shaped error for the same bad input — the
// backend stays the sole validator, same "thin relay" principle as
// readJSONBody's byte-for-byte body forwarding.
var auditFilterParams = []string{"event_type", "from", "to", "user_id", "client_id", "actor"}

// auditQuery rebuilds the audit-events query string from an allow-list of
// the six filters above plus page/size (via pagingQuery) — any OTHER query
// parameter on the incoming request is silently dropped, never forwarded.
func auditQuery(r *http.Request) url.Values {
	values := pagingQuery(r)
	incoming := r.URL.Query()
	for _, name := range auditFilterParams {
		if v := incoming.Get(name); v != "" {
			values.Set(name, v)
		}
	}
	return values
}

func (s *Server) handleAdminAuditEventsList(w http.ResponseWriter, r *http.Request) {
	slug := chi.URLParam(r, "slug")
	session, ok := s.adminSessionOrError(w, r)
	if !ok {
		return
	}
	resp, err := s.adminClient.GetQuery(r.Context(), session.AccessToken,
		"/v1/tenants/"+session.TenantID+"/audit-events", auditQuery(r))
	if err != nil {
		writeJSONError(w, http.StatusBadGateway, "bad_gateway", "Could not reach the backend.")
		return
	}
	s.writeAdminAPIResult(w, slug, session, resp)
}
