package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAdminSession_RoundTrip(t *testing.T) {
	w := httptest.NewRecorder()
	session := &AdminSession{
		TenantID:       "tenant-1",
		UserID:         "user-1",
		Email:          "admin@acme.test",
		TenantRoles:    []string{"TENANT_ADMIN"},
		ClientID:       "admin-console-acme",
		AccessToken:    "eyJ.fake.token",
		AccessTokenExp: time.Now().Add(5 * time.Minute).Unix(),
	}
	if err := SetAdminSession(httptest.NewRecorder(), "acme", session, false, 12*time.Hour); err != nil {
		t.Fatalf("SetAdminSession() error = %v", err)
	}

	// Re-do against a fresh recorder we can read cookies back from.
	w = httptest.NewRecorder()
	if err := SetAdminSession(w, "acme", session, false, 12*time.Hour); err != nil {
		t.Fatalf("SetAdminSession() error = %v", err)
	}

	req := httptest.NewRequest("GET", "/t/acme/api/session", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}

	got, err := GetAdminSession(req, "acme")
	if err != nil {
		t.Fatalf("GetAdminSession() error = %v", err)
	}
	if got.Slug != "acme" || got.TenantID != "tenant-1" || got.Email != "admin@acme.test" ||
		!got.IsTenantAdmin() {
		t.Errorf("GetAdminSession() = %+v", got)
	}
}

func TestAdminSession_CookieIsScopedToTenantPath(t *testing.T) {
	w := httptest.NewRecorder()
	session := &AdminSession{AccessTokenExp: time.Now().Add(time.Hour).Unix()}
	if err := SetAdminSession(w, "acme", session, true, 12*time.Hour); err != nil {
		t.Fatalf("SetAdminSession() error = %v", err)
	}

	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	c := cookies[0]
	if c.Name != AdminSessionCookieName {
		t.Errorf("Name = %q", c.Name)
	}
	if c.Path != "/t/acme" {
		t.Errorf("Path = %q, want /t/acme", c.Path)
	}
	if c.MaxAge != int((12 * time.Hour).Seconds()) {
		t.Errorf("MaxAge = %d", c.MaxAge)
	}
}

func TestGetAdminSession_CiphertextFromOneTenantFailsForAnother(t *testing.T) {
	w := httptest.NewRecorder()
	session := &AdminSession{AccessTokenExp: time.Now().Add(time.Hour).Unix()}
	_ = SetAdminSession(w, "acme", session, false, time.Hour)

	// Simulate presenting the acme cookie's value at globex's path — same
	// cookie NAME, different AAD binding, must not open.
	acmeCookie := w.Result().Cookies()[0]
	req := httptest.NewRequest("GET", "/t/globex/api/session", nil)
	req.AddCookie(&http.Cookie{Name: AdminSessionCookieName, Value: acmeCookie.Value})

	if _, err := GetAdminSession(req, "globex"); err == nil {
		t.Error("GetAdminSession() with another tenant's ciphertext should fail, got nil error")
	}
}

func TestAdminSession_IsTenantAdmin(t *testing.T) {
	admin := &AdminSession{TenantRoles: []string{"TENANT_MEMBER", "TENANT_ADMIN"}}
	if !admin.IsTenantAdmin() {
		t.Error("IsTenantAdmin() = false, want true")
	}

	member := &AdminSession{TenantRoles: []string{"TENANT_MEMBER"}}
	if member.IsTenantAdmin() {
		t.Error("IsTenantAdmin() = true, want false")
	}

	empty := &AdminSession{}
	if empty.IsTenantAdmin() {
		t.Error("IsTenantAdmin() on empty roles = true, want false")
	}
}

func TestAdminSession_NeedsReauth(t *testing.T) {
	now := time.Now()

	freshlyExpiring := &AdminSession{AccessTokenExp: now.Add(10 * time.Second).Unix()}
	if !freshlyExpiring.NeedsReauth(now, 30*time.Second) {
		t.Error("NeedsReauth() = false for a token expiring within skew, want true")
	}

	healthy := &AdminSession{AccessTokenExp: now.Add(5 * time.Minute).Unix()}
	if healthy.NeedsReauth(now, 30*time.Second) {
		t.Error("NeedsReauth() = true for a healthy token, want false")
	}

	alreadyExpired := &AdminSession{AccessTokenExp: now.Add(-1 * time.Minute).Unix()}
	if !alreadyExpired.NeedsReauth(now, 30*time.Second) {
		t.Error("NeedsReauth() = false for an already-expired token, want true")
	}
}

func TestClearAdminSession(t *testing.T) {
	w := httptest.NewRecorder()
	ClearAdminSession(w, "acme", true)

	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	if cookies[0].Path != "/t/acme" || cookies[0].MaxAge != -1 || !cookies[0].Secure {
		t.Errorf("ClearAdminSession() cookie = %+v", cookies[0])
	}
}

func TestAdminDenied_RoundTrip(t *testing.T) {
	w := httptest.NewRecorder()
	if err := SetAdminDenied(w, "acme", "not_tenant_admin", "user-1", false); err != nil {
		t.Fatalf("SetAdminDenied() error = %v", err)
	}

	req := httptest.NewRequest("GET", "/t/acme/admin/login", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}

	got, err := GetAdminDenied(req, "acme")
	if err != nil {
		t.Fatalf("GetAdminDenied() error = %v", err)
	}
	if got.Slug != "acme" || got.Reason != "not_tenant_admin" || got.Sub != "user-1" {
		t.Errorf("GetAdminDenied() = %+v", got)
	}
}

func TestClearAdminDenied(t *testing.T) {
	w := httptest.NewRecorder()
	setW := httptest.NewRecorder()
	_ = SetAdminDenied(setW, "acme", "not_tenant_admin", "user-1", false)
	req := httptest.NewRequest("GET", "/t/acme/admin/login", nil)
	for _, c := range setW.Result().Cookies() {
		req.AddCookie(c)
	}

	ClearAdminDenied(w, "acme", false)
	clearedReq := httptest.NewRequest("GET", "/t/acme/admin/login", nil)
	for _, c := range w.Result().Cookies() {
		clearedReq.AddCookie(c)
	}
	// The cleared cookie carries an empty value, which won't decrypt.
	if _, err := GetAdminDenied(clearedReq, "acme"); err == nil {
		t.Error("GetAdminDenied() after ClearAdminDenied should fail, got nil error")
	}
}
