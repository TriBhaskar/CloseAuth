package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"closeauth-frontend/internal/middleware"
	"closeauth-frontend/internal/proxy"
)

// TestAdminRoutes_ZeroValueServer_RegisterRoutesDoesNotPanic is the
// structural analogue of login_json_test.go's nil-logger rule: every
// pre-existing *_test.go in this package constructs a partial *Server (e.g.
// &Server{authProxy: proxy.New(...)}) with no bff/oauthClient/adminClient
// set. RegisterRoutes and every admin handler must tolerate that via
// bffConfig()'s nil-safe default rather than panicking.
func TestAdminRoutes_ZeroValueServer_RegisterRoutesDoesNotPanic(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// Hitting the session probe (no session-required auth) must not panic
	// and must return a well-formed response even with no oauthClient/
	// adminClient wired.
	resp, err := http.Get(ts.URL + "/t/acme/api/session")
	if err != nil {
		t.Fatalf("GET session probe: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want 200 (session probe always succeeds)", resp.StatusCode)
	}

	// A guarded route with no oauthClient/adminClient configured and no
	// session cookie must fail cleanly (401), not panic.
	pingResp, err := http.Get(ts.URL + "/t/acme/api/ping")
	if err != nil {
		t.Fatalf("GET ping: %v", err)
	}
	defer pingResp.Body.Close()
	if pingResp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401 (no session)", pingResp.StatusCode)
	}
}

// TestAdminAPI_MutatingRequestWithoutCSRFToken_Returns403 confirms
// CSRFValidationMiddleware is actually mounted on the /t/{slug}/api group.
func TestAdminAPI_MutatingRequestWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	resp, err := http.Post(ts.URL+"/t/acme/api/signout", "application/json", nil)
	if err != nil {
		t.Fatalf("POST signout: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("status = %d, want 403 (missing CSRF token)", resp.StatusCode)
	}
}

// TestAdminAPI_MissingSession_Returns401Unauthenticated confirms
// RequireAdminSession is mounted on /ping specifically (not the whole /api
// group — signout/session/denied-dismiss must remain reachable without a
// session).
func TestAdminAPI_MissingSession_Returns401Unauthenticated(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/t/acme/api/ping")
	if err != nil {
		t.Fatalf("GET ping: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", resp.StatusCode)
	}
}

// TestAdminCallback_StateMismatch_RedirectsToAuthErrorNeverReinitiates
// confirms a tampered/stale state parameter dead-ends at auth-error rather
// than silently retrying authorize (which would be a fresh attack surface,
// not a recovery path).
func TestAdminCallback_StateMismatch_RedirectsToAuthErrorNeverReinitiates(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// A callback with a state that doesn't correspond to any saved
	// oauth_ctx_{slug} cookie at all.
	state, err := middleware.NewState("acme")
	if err != nil {
		t.Fatalf("NewState: %v", err)
	}
	resp, err := client.Get(ts.URL + "/admin/callback?code=whatever&state=" + state)
	if err != nil {
		t.Fatalf("GET callback: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusFound {
		t.Fatalf("status = %d, want 302", resp.StatusCode)
	}
	location := resp.Header.Get("Location")
	if !strings.Contains(location, "/t/acme/auth-error") || !strings.Contains(location, "reason=invalid_state") {
		t.Errorf("Location = %q, want a redirect to /t/acme/auth-error?reason=invalid_state", location)
	}
	if strings.Contains(location, "/oauth2/authorize") {
		t.Errorf("Location = %q must NOT re-initiate authorize on a state mismatch", location)
	}
}

// TestAdminAuthStart_RejectsInvalidSlug confirms a malformed slug path
// segment is rejected before it's ever interpolated into a cookie name/path
// or redirect target.
func TestAdminAuthStart_RejectsInvalidSlug(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// Uppercase + underscore: not a valid slug per slugPattern.
	resp, err := client.Get(ts.URL + "/t/NOT_a_valid_SLUG!/admin/login")
	if err != nil {
		t.Fatalf("GET admin/login: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusFound {
		t.Fatalf("status = %d, want 302", resp.StatusCode)
	}
	if loc := resp.Header.Get("Location"); !strings.Contains(loc, "reason=invalid_slug") {
		t.Errorf("Location = %q, want a redirect carrying reason=invalid_slug", loc)
	}
}

func TestSafeReturnTo_RejectsOffTenantAndAbsoluteURLs(t *testing.T) {
	tests := []struct {
		name string
		slug string
		raw  string
		want string
	}{
		{"empty falls back to default", "acme", "", "/t/acme/console"},
		{"same-tenant path is allowed", "acme", "/t/acme/settings", "/t/acme/settings"},
		{"off-tenant path falls back to default", "acme", "/t/globex/console", "/t/acme/console"},
		{"absolute external URL falls back to default", "acme", "https://evil.test/phish", "/t/acme/console"},
		{"protocol-relative URL falls back to default", "acme", "//evil.test/phish", "/t/acme/console"},
		{"root path (no tenant prefix) falls back to default", "acme", "/", "/t/acme/console"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := safeReturnTo(tt.slug, tt.raw); got != tt.want {
				t.Errorf("safeReturnTo(%q, %q) = %q, want %q", tt.slug, tt.raw, got, tt.want)
			}
		})
	}
}

// TestAdminUsersRoutes_MissingSession_Returns401 confirms every UI-3b
// user/role route sits inside the RequireAdminSession-guarded group (not
// accidentally mounted on the parent /api router alongside session/signout/
// denied-dismiss, which must remain reachable without a session).
func TestAdminUsersRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	paths := []string{
		"/t/acme/api/users",
		"/t/acme/api/users/" + userID,
		"/t/acme/api/roles",
		"/t/acme/api/users/" + userID + "/tenant-roles",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403 confirms the new
// POST/DELETE routes inherit CSRFValidationMiddleware from the /api
// subrouter (mirrors TestAdminAPI_MutatingRequestWithoutCSRFToken_Returns403
// for the pre-existing /signout route) — CSRF is checked before
// RequireAdminSession, so this holds even with no session cookie at all.
func TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	roleID := "22222222-2222-2222-2222-222222222222"

	cases := []struct{ method, path string }{
		{http.MethodPost, "/t/acme/api/users"},
		{http.MethodPost, "/t/acme/api/users/" + userID + "/suspend"},
		{http.MethodPost, "/t/acme/api/users/" + userID + "/activate"},
		{http.MethodPost, "/t/acme/api/users/" + userID + "/approve"},
		{http.MethodDelete, "/t/acme/api/users/" + userID},
		{http.MethodPost, "/t/acme/api/users/" + userID + "/tenant-roles/" + roleID},
		{http.MethodDelete, "/t/acme/api/users/" + userID + "/tenant-roles/" + roleID},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestAdminClientsAndResourceServersRoutes_MissingSession_Returns401 is the
// UI-3c counterpart of TestAdminUsersRoutes_MissingSession_Returns401 — every
// new client/resource-server/scope route must sit inside the
// RequireAdminSession-guarded group.
func TestAdminClientsAndResourceServersRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	clientID := "11111111-1111-1111-1111-111111111111"
	rsID := "22222222-2222-2222-2222-222222222222"
	paths := []string{
		"/t/acme/api/clients/" + clientID,
		"/t/acme/api/resource-servers",
		"/t/acme/api/resource-servers/" + rsID,
		"/t/acme/api/resource-servers/" + rsID + "/scopes",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestAdminClientsAndResourceServersRoutes_MutatingWithoutCSRFToken_Returns403
// is the UI-3c counterpart of TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403.
func TestAdminClientsAndResourceServersRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	clientID := "11111111-1111-1111-1111-111111111111"
	rsID := "22222222-2222-2222-2222-222222222222"
	scopeID := "33333333-3333-3333-3333-333333333333"

	cases := []struct{ method, path string }{
		{http.MethodPost, "/t/acme/api/clients"},
		{http.MethodPost, "/t/acme/api/clients/" + clientID + "/client-secret"},
		{http.MethodPost, "/t/acme/api/resource-servers"},
		{http.MethodPatch, "/t/acme/api/resource-servers/" + rsID},
		{http.MethodDelete, "/t/acme/api/resource-servers/" + rsID},
		{http.MethodPost, "/t/acme/api/resource-servers/" + rsID + "/scopes"},
		{http.MethodPatch, "/t/acme/api/resource-servers/" + rsID + "/scopes/" + scopeID},
		{http.MethodDelete, "/t/acme/api/resource-servers/" + rsID + "/scopes/" + scopeID},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestAdminSettingsAndAuditRoutes_MissingSession_Returns401 is the UI-3e
// counterpart of TestAdminUsersRoutes_MissingSession_Returns401 — branding,
// registration-config, and audit-events must all sit inside the
// RequireAdminSession-guarded group.
func TestAdminSettingsAndAuditRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	paths := []string{
		"/t/acme/api/branding",
		"/t/acme/api/registration-config",
		"/t/acme/api/audit-events",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestAdminSettingsRoutes_MutatingWithoutCSRFToken_Returns403 is the UI-3e
// counterpart of TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403 —
// audit-events has no mutating verb (read-only surface), so only branding
// and registration-config PUTs are covered here.
func TestAdminSettingsRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	cases := []struct{ method, path string }{
		{http.MethodPut, "/t/acme/api/branding"},
		{http.MethodPut, "/t/acme/api/registration-config"},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestValidUUID guards every place a path-supplied {userId}/{roleId} is
// interpolated into a backend admin-API URL — a malformed id must be
// rejected locally, never forwarded.
func TestValidUUID(t *testing.T) {
	valid := []string{
		"11111111-1111-1111-1111-111111111111",
		"AAAAAAAA-bbbb-CCCC-dddd-eeeeeeeeeeee",
	}
	invalid := []string{
		"",
		"not-a-uuid",
		"11111111-1111-1111-1111-11111111111",  // one char short
		"111111111111111111111111111111111111", // no hyphens
		"11111111-1111-1111-1111-111111111111; DROP TABLE users; --",
	}
	for _, s := range valid {
		if !validUUID(s) {
			t.Errorf("validUUID(%q) = false, want true", s)
		}
	}
	for _, s := range invalid {
		if validUUID(s) {
			t.Errorf("validUUID(%q) = true, want false", s)
		}
	}
}

// TestUserFilterQuery_AllowListsOnlyTheDocumentedFilters is the cheap,
// always-run structural counterpart of the Docker-gated paging test — proves
// userFilterQuery forwards page/size + status/role/q and silently drops
// anything else, mirroring auditQuery's own allow-list shape.
func TestUserFilterQuery_AllowListsOnlyTheDocumentedFilters(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet,
		"http://example.test/t/acme/api/users?page=1&size=10&status=ACTIVE&role=TENANT_ADMIN&q=ada&sort=email&bogus=1",
		nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}

	got := userFilterQuery(req)

	want := map[string]string{"page": "1", "size": "10", "status": "ACTIVE", "role": "TENANT_ADMIN", "q": "ada"}
	for k, v := range want {
		if got.Get(k) != v {
			t.Errorf("userFilterQuery()[%q] = %q, want %q", k, got.Get(k), v)
		}
	}
	for _, dropped := range []string{"sort", "bogus"} {
		if got.Has(dropped) {
			t.Errorf("userFilterQuery() forwarded %q, want it silently dropped", dropped)
		}
	}
}

func TestValidSlug(t *testing.T) {
	// Bug fix (found via manual testing against a real ten_-prefixed
	// tenant): the pattern originally had no ten_-prefixed alternative at
	// all, so a real post-BE-A tenant slug like "ten_rohit" failed this
	// check and broke handleAdminCallback's own slug extraction —
	// see slugPattern's own doc comment for the full story.
	valid := []string{
		"acme", "t-c9224622", "a", strings.Repeat("a", 63), // legacy bare shape, still accepted
		"ten_acme-inc", "ten_rohit", "ten_a1", // ten_-prefixed, the real post-BE-A production shape
	}
	invalid := []string{
		"", "Acme", "acme_corp", "-acme", strings.Repeat("a", 64), "acme/../etc",
		"TEN_acme", "ten_", // malformed ten_-prefixed shapes: wrong case, no suffix at all
	}

	for _, s := range valid {
		if !validSlug(s) {
			t.Errorf("validSlug(%q) = false, want true", s)
		}
	}
	for _, s := range invalid {
		if validSlug(s) {
			t.Errorf("validSlug(%q) = true, want false", s)
		}
	}
}

// TestAdminSession_NoTokenLeaksInSessionResponse guards the "no token ever
// appears in a response body" invariant at the JSON-shape level (the
// Docker-gated round-trip test in admin_console_test.go re-proves it against
// a REAL token; this is the cheap, always-run structural counterpart).
func TestAdminSession_NoTokenLeaksInSessionResponse(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// Build a real session cookie the way the callback would, then present it.
	w := httptest.NewRecorder()
	session := &middleware.AdminSession{
		TenantID:       "tenant-1",
		UserID:         "user-1",
		TenantRoles:    []string{"TENANT_ADMIN"},
		AccessToken:    "eyJhbGciOiJSUzI1NiJ9.super-secret-payload.sig",
		AccessTokenExp: time.Now().Add(time.Hour).Unix(),
	}
	if err := middleware.SetAdminSession(w, "acme", session, false, 12*time.Hour); err != nil {
		t.Fatalf("SetAdminSession: %v", err)
	}

	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/t/acme/api/session", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET session: %v", err)
	}
	defer resp.Body.Close()

	body := make([]byte, 0, 4096)
	buf := make([]byte, 4096)
	for {
		n, err := resp.Body.Read(buf)
		body = append(body, buf[:n]...)
		if err != nil {
			break
		}
	}
	bodyStr := string(body)
	if strings.Contains(bodyStr, "access_token") || strings.Contains(bodyStr, "eyJ") || strings.Contains(bodyStr, "super-secret-payload") {
		t.Errorf("session response leaked the access token: %s", bodyStr)
	}
	if !strings.Contains(bodyStr, "TENANT_ADMIN") {
		t.Errorf("session response missing expected tenantRoles: %s", bodyStr)
	}
}

// TestAdminRolesRoutes_MissingSession_Returns401 is the UI-3d counterpart of
// TestAdminUsersRoutes_MissingSession_Returns401 — every new tenant-role and
// application-role route must sit inside the RequireAdminSession-guarded
// group.
func TestAdminRolesRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	roleID := "22222222-2222-2222-2222-222222222222"
	rsID := "33333333-3333-3333-3333-333333333333"
	paths := []string{
		"/t/acme/api/roles/" + roleID,
		"/t/acme/api/roles/" + roleID + "/assignees",
		"/t/acme/api/resource-servers/" + rsID + "/roles",
		"/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID,
		"/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID + "/assignees",
		"/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID + "/scopes",
		"/t/acme/api/users/" + userID + "/application-roles?resourceServerId=" + rsID,
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestAdminRolesRoutes_MutatingWithoutCSRFToken_Returns403 is the UI-3d
// counterpart of TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403.
func TestAdminRolesRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	roleID := "22222222-2222-2222-2222-222222222222"
	rsID := "33333333-3333-3333-3333-333333333333"
	scopeID := "44444444-4444-4444-4444-444444444444"

	cases := []struct{ method, path string }{
		{http.MethodPost, "/t/acme/api/roles"},
		{http.MethodPatch, "/t/acme/api/roles/" + roleID},
		{http.MethodDelete, "/t/acme/api/roles/" + roleID},
		{http.MethodPost, "/t/acme/api/resource-servers/" + rsID + "/roles"},
		{http.MethodPatch, "/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID},
		{http.MethodDelete, "/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID},
		{http.MethodPost, "/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID + "/scopes/" + scopeID},
		{http.MethodDelete, "/t/acme/api/resource-servers/" + rsID + "/roles/" + roleID + "/scopes/" + scopeID},
		{http.MethodPost, "/t/acme/api/users/" + userID + "/application-roles/" + roleID},
		{http.MethodDelete, "/t/acme/api/users/" + userID + "/application-roles/" + roleID},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestAdminUserSessionsAndInvitesRoutes_MissingSession_Returns401 is the
// FE-4a counterpart of TestAdminUsersRoutes_MissingSession_Returns401 — the
// new Sessions-tab and invitation routes must sit inside the same
// RequireAdminSession-guarded group as every other UI-3b/3d route.
func TestAdminUserSessionsAndInvitesRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	paths := []string{
		"/t/acme/api/users/" + userID + "/sessions",
		"/t/acme/api/invites",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestAdminUserSessionsAndInvitesRoutes_MutatingWithoutCSRFToken_Returns403
// is the FE-4a counterpart of TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403.
func TestAdminUserSessionsAndInvitesRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	userID := "11111111-1111-1111-1111-111111111111"
	sessionID := "22222222-2222-2222-2222-222222222222"
	inviteID := "33333333-3333-3333-3333-333333333333"

	cases := []struct{ method, path string }{
		{http.MethodPost, "/t/acme/api/users/with-temp-credential"},
		{http.MethodDelete, "/t/acme/api/users/" + userID + "/sessions/" + sessionID},
		{http.MethodDelete, "/t/acme/api/users/" + userID + "/sessions"},
		{http.MethodPost, "/t/acme/api/invites"},
		{http.MethodDelete, "/t/acme/api/invites/" + inviteID},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestPlatformRoutes_MissingSession_Returns401 is the /platform/api/**
// counterpart of TestAdminUsersRoutes_MissingSession_Returns401 — that group
// previously had no always-run structural tests at all (only the
// Docker-gated platform_console_test.go / platform_onboarding_test.go
// exercise it against a real stack). Covers the full existing surface
// (tenants, admins) plus Stage UI-4b's three new onboarding routes, so a
// route accidentally registered outside RequirePlatformSession's group would
// be caught without needing Docker.
func TestPlatformRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	tenantID := "11111111-1111-1111-1111-111111111111"
	adminID := "33333333-3333-3333-3333-333333333333"
	paths := []string{
		"/platform/api/tenants",
		"/platform/api/tenants/" + tenantID,
		"/platform/api/tenants/" + tenantID + "/users",
		"/platform/api/admins",
		"/platform/api/admins/" + adminID + "/roles",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestPlatformRoutes_MutatingWithoutCSRFToken_Returns403 is the
// /platform/api/** counterpart of TestAdminUsersRoutes_MutatingWithoutCSRFToken_Returns403
// — CSRFValidationMiddleware sits on the /platform/api subrouter, ahead of
// RequirePlatformSession, so this holds even with no session cookie at all
// (see platform_api_result.go's own doc comment on CSRF-before-session
// ordering).
func TestPlatformRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	tenantID := "11111111-1111-1111-1111-111111111111"
	userID := "22222222-2222-2222-2222-222222222222"
	adminID := "33333333-3333-3333-3333-333333333333"

	cases := []struct{ method, path string }{
		{http.MethodPost, "/platform/api/tenants"},
		{http.MethodPost, "/platform/api/tenants/" + tenantID + "/activate"},
		{http.MethodDelete, "/platform/api/tenants/" + tenantID},
		{http.MethodPost, "/platform/api/tenants/" + tenantID + "/bootstrap-admin"},
		{http.MethodPost, "/platform/api/tenants/" + tenantID + "/users/" + userID + "/reissue-onboarding-credential"},
		{http.MethodPost, "/platform/api/admins"},
		{http.MethodPost, "/platform/api/admins/" + adminID + "/suspend"},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestMeRoutes_MissingSession_Returns401 is FE-4d's counterpart of
// TestAdminUserSessionsAndInvitesRoutes_MissingSession_Returns401 — proves
// the four self-service /me/** routes are gated at all (by
// RequireTenantSession, not RequireAdminSession — but with no cookie at
// all, both gates refuse identically: 401 unauthenticated).
func TestMeRoutes_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	// GET only here — the mutating routes (DELETE .../sessions/{id}, POST
	// .../change-password) hit CSRFValidationMiddleware first (mounted at
	// the ar router level, ahead of RequireTenantSession), so a sessionless
	// mutating request 403s before the session check ever runs. That
	// ordering is proven by TestMeRoutes_MutatingWithoutCSRFToken_Returns403
	// below, same split TestAdminUserSessionsAndInvitesRoutes_* already uses.
	paths := []string{
		"/t/acme/api/me",
		"/t/acme/api/me/sessions",
	}
	for _, p := range paths {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("GET %s: %v", p, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s: status = %d, want 401 (no session)", p, resp.StatusCode)
		}
	}
}

// TestMeRoutes_MutatingWithoutCSRFToken_Returns403 is FE-4d's counterpart of
// TestAdminUserSessionsAndInvitesRoutes_MutatingWithoutCSRFToken_Returns403.
func TestMeRoutes_MutatingWithoutCSRFToken_Returns403(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	sessionID := "11111111-1111-1111-1111-111111111111"
	cases := []struct{ method, path string }{
		{http.MethodPost, "/t/acme/api/me/change-password"},
		{http.MethodDelete, "/t/acme/api/me/sessions/" + sessionID},
	}
	for _, c := range cases {
		req, err := http.NewRequest(c.method, ts.URL+c.path, nil)
		if err != nil {
			t.Fatalf("build %s %s: %v", c.method, c.path, err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s: status = %d, want 403 (missing CSRF token)", c.method, c.path, resp.StatusCode)
		}
	}
}

// TestAdminClientCountRoute_MissingSession_Returns401 is FE-4d's counterpart
// covering the new /clients/count tile-backing route (admin-gated, unlike
// /me/** above — it lives in the existing pr.Group).
func TestAdminClientCountRoute_MissingSession_Returns401(t *testing.T) {
	s := &Server{authProxy: proxy.New("http://localhost:9999")}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/t/acme/api/clients/count")
	if err != nil {
		t.Fatalf("GET clients/count: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("GET clients/count: status = %d, want 401 (no session)", resp.StatusCode)
	}
}
