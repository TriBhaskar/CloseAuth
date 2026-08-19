package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func slugFromChiLikeParam(slug string) SlugFunc {
	return func(r *http.Request) string { return slug }
}

func setSessionCookie(t *testing.T, slug string, session *AdminSession) *http.Request {
	t.Helper()
	w := httptest.NewRecorder()
	if err := SetAdminSession(w, slug, session, false, 12*time.Hour); err != nil {
		t.Fatalf("SetAdminSession() error = %v", err)
	}
	req := httptest.NewRequest("GET", "/t/"+slug+"/api/ping", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}
	return req
}

func TestRequireAdminSession_NoCookie_Returns401Unauthenticated(t *testing.T) {
	handlerCalled := false
	h := RequireAdminSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { handlerCalled = true }))

	req := httptest.NewRequest("GET", "/t/acme/api/ping", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	if handlerCalled {
		t.Error("downstream handler should not be called")
	}
	if body := rec.Body.String(); !strings.Contains(body, `"unauthenticated"`) {
		t.Errorf("body = %s, want unauthenticated", body)
	}
}

func TestRequireAdminSession_NonAdmin_Returns403(t *testing.T) {
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantRoles:    []string{"TENANT_MEMBER"},
		AccessTokenExp: time.Now().Add(time.Hour).Unix(),
	})

	h := RequireAdminSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Error("downstream handler should not be called for a non-admin")
		}))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
	if body := rec.Body.String(); !strings.Contains(body, `"not_tenant_admin"`) {
		t.Errorf("body = %s, want not_tenant_admin", body)
	}
}

func TestRequireAdminSession_NearExpiry_Returns401ReauthRequired(t *testing.T) {
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantRoles:    []string{"TENANT_ADMIN"},
		AccessTokenExp: time.Now().Add(5 * time.Second).Unix(), // within the 30s skew below
	})

	h := RequireAdminSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Error("downstream handler should not be called for a near-expiry token")
		}))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `"reauth_required"`) || !strings.Contains(body, `/t/acme/admin/reauth`) {
		t.Errorf("body = %s, want reauth_required with reauthPath", body)
	}
}

func TestRequireAdminSession_HealthyAdmin_CallsNextWithSessionInContext(t *testing.T) {
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantID:       "tenant-1",
		TenantRoles:    []string{"TENANT_ADMIN"},
		AccessTokenExp: time.Now().Add(5 * time.Minute).Unix(),
	})

	var gotSession *AdminSession
	var gotOK bool
	h := RequireAdminSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotSession, gotOK = AdminSessionFrom(r.Context())
			w.WriteHeader(http.StatusOK)
		}))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}
	if !gotOK || gotSession == nil || gotSession.TenantID != "tenant-1" {
		t.Errorf("AdminSessionFrom() = %+v, %v", gotSession, gotOK)
	}
}

// ---- RequireTenantSession (FE-4d) ----------------------------------------

func TestRequireTenantSession_NoCookie_Returns401Unauthenticated(t *testing.T) {
	handlerCalled := false
	h := RequireTenantSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { handlerCalled = true }))

	req := httptest.NewRequest("GET", "/t/acme/api/me", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	if handlerCalled {
		t.Error("downstream handler should not be called")
	}
	if body := rec.Body.String(); !strings.Contains(body, `"unauthenticated"`) {
		t.Errorf("body = %s, want unauthenticated", body)
	}
}

func TestRequireTenantSession_NonAdmin_CallsNext(t *testing.T) {
	// The whole point of this gate: unlike RequireAdminSession, a session
	// with NO TENANT_ADMIN role (or no tenant_roles at all) is accepted —
	// FE-4.14's /account must be reachable by every tenant user, admin or
	// not.
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantID:       "tenant-1",
		TenantRoles:    []string{},
		AccessTokenExp: time.Now().Add(time.Hour).Unix(),
	})

	var gotSession *AdminSession
	var gotOK bool
	h := RequireTenantSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotSession, gotOK = AdminSessionFrom(r.Context())
			w.WriteHeader(http.StatusOK)
		}))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 (a non-admin session must still pass this gate)", rec.Code)
	}
	if !gotOK || gotSession == nil || gotSession.TenantID != "tenant-1" {
		t.Errorf("AdminSessionFrom() = %+v, %v", gotSession, gotOK)
	}
}

func TestRequireTenantSession_HealthyAdmin_CallsNext(t *testing.T) {
	// An admin has a valid tenant session too — this gate is strictly
	// weaker than RequireAdminSession, never mutually exclusive with it.
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantID:       "tenant-1",
		TenantRoles:    []string{"TENANT_ADMIN"},
		AccessTokenExp: time.Now().Add(time.Hour).Unix(),
	})

	h := RequireTenantSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) }))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}
}

func TestRequireTenantSession_NearExpiry_Returns401ReauthRequired(t *testing.T) {
	req := setSessionCookie(t, "acme", &AdminSession{
		TenantRoles:    []string{},
		AccessTokenExp: time.Now().Add(5 * time.Second).Unix(), // within the 30s skew below
	})

	h := RequireTenantSession(slugFromChiLikeParam("acme"), 30*time.Second)(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Error("downstream handler should not be called for a near-expiry token")
		}))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `"reauth_required"`) || !strings.Contains(body, `/t/acme/admin/reauth`) {
		t.Errorf("body = %s, want reauth_required with reauthPath", body)
	}
}

func TestAdminSessionFrom_NotSet_ReturnsFalse(t *testing.T) {
	req := httptest.NewRequest("GET", "/", nil)
	session, ok := AdminSessionFrom(req.Context())
	if ok || session != nil {
		t.Errorf("AdminSessionFrom() on a bare context = %+v, %v, want nil, false", session, ok)
	}
}
