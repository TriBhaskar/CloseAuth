package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClearCookie_SetsMatchingAttributes(t *testing.T) {
	w := httptest.NewRecorder()
	clearCookie(w, "bff_admin_session", "/t/acme", true)

	resp := w.Result()
	cookies := resp.Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	c := cookies[0]
	if c.Name != "bff_admin_session" {
		t.Errorf("Name = %q", c.Name)
	}
	if c.Path != "/t/acme" {
		t.Errorf("Path = %q, want /t/acme", c.Path)
	}
	if c.MaxAge != -1 {
		t.Errorf("MaxAge = %d, want -1", c.MaxAge)
	}
	if !c.Secure {
		t.Error("Secure = false, want true")
	}
	if !c.HttpOnly {
		t.Error("HttpOnly = false, want true")
	}
	if c.SameSite != http.SameSiteLaxMode {
		t.Errorf("SameSite = %v, want Lax", c.SameSite)
	}
}

func TestClearCookie_SecureFalseWhenNotProduction(t *testing.T) {
	w := httptest.NewRecorder()
	clearCookie(w, "csrf_token", "/", false)

	c := w.Result().Cookies()[0]
	if c.Secure {
		t.Error("Secure = true, want false")
	}
}
