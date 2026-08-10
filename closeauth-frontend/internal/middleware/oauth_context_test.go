package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestOAuthContext_RoundTrip(t *testing.T) {
	w := httptest.NewRecorder()
	ctx := &OAuthContext{
		ClientID:     "admin-console-acme",
		RedirectURI:  "http://localhost:8080/admin/callback",
		Scope:        "openid profile",
		State:        "st-123",
		CodeVerifier: "verifier-abc",
		ReturnTo:     "/t/acme/console",
		Attempt:      1,
	}
	if err := SaveOAuthContext(w, "acme", ctx, false); err != nil {
		t.Fatalf("SaveOAuthContext() error = %v", err)
	}

	req := httptest.NewRequest("GET", "/admin/callback", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}

	got, err := GetOAuthContext(req, "acme")
	if err != nil {
		t.Fatalf("GetOAuthContext() error = %v", err)
	}
	if got.Slug != "acme" || got.ClientID != "admin-console-acme" || got.CodeVerifier != "verifier-abc" ||
		got.ReturnTo != "/t/acme/console" || got.Attempt != 1 {
		t.Errorf("GetOAuthContext() = %+v", got)
	}
}

func TestOAuthContext_CookieAttributes(t *testing.T) {
	w := httptest.NewRecorder()
	_ = SaveOAuthContext(w, "acme", &OAuthContext{}, true)

	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	c := cookies[0]
	if c.Name != "oauth_ctx_acme" {
		t.Errorf("Name = %q", c.Name)
	}
	if c.Path != "/" {
		t.Errorf("Path = %q, want /", c.Path)
	}
	if !c.Secure {
		t.Error("Secure should be true when isProduction=true")
	}
	// SameSite=Lax is load-bearing (see the doc comment on
	// OAuthContextCookieName / SaveOAuthContext): the callback is reached
	// via a cross-site top-level redirect FROM the backend, which Strict
	// would withhold this cookie for.
	if c.SameSite != http.SameSiteLaxMode {
		t.Errorf("SameSite = %v, want Lax", c.SameSite)
	}
}

func TestGetOAuthContext_WrongSlug_Fails(t *testing.T) {
	w := httptest.NewRecorder()
	_ = SaveOAuthContext(w, "acme", &OAuthContext{State: "st-1"}, false)

	req := httptest.NewRequest("GET", "/admin/callback", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}

	// No oauth_ctx_globex cookie exists at all, so this must fail to find one.
	if _, err := GetOAuthContext(req, "globex"); err == nil {
		t.Error("GetOAuthContext() for a different slug should fail, got nil error")
	}
}

func TestGetOAuthContext_MissingCookie_Fails(t *testing.T) {
	req := httptest.NewRequest("GET", "/admin/callback", nil)
	if _, err := GetOAuthContext(req, "acme"); err == nil {
		t.Error("GetOAuthContext() with no cookie should fail, got nil error")
	}
}

func TestGetOAuthContext_Expired_Fails(t *testing.T) {
	SetOAuthContextTTL(1)
	defer SetOAuthContextTTL(600)

	w := httptest.NewRecorder()
	_ = SaveOAuthContext(w, "acme", &OAuthContext{State: "st-1"}, false)

	req := httptest.NewRequest("GET", "/admin/callback", nil)
	for _, c := range w.Result().Cookies() {
		req.AddCookie(c)
	}

	// The expiry check compares whole-second Unix() timestamps, so sleeping
	// just past the 1s TTL isn't reliably enough margin (floor truncation
	// can make the observed diff exactly 1, not > 1, depending on where
	// within the current second Save landed). Sleep past 2 TTL-seconds to
	// make the diff deterministically exceed the TTL regardless of timing.
	time.Sleep(2200 * time.Millisecond)

	if _, err := GetOAuthContext(req, "acme"); err == nil {
		t.Error("GetOAuthContext() past TTL should fail, got nil error")
	}
}

func TestClearOAuthContext(t *testing.T) {
	w := httptest.NewRecorder()
	ClearOAuthContext(w, "acme", true)

	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	if cookies[0].MaxAge != -1 {
		t.Errorf("MaxAge = %d, want -1", cookies[0].MaxAge)
	}
	if !cookies[0].Secure {
		t.Error("Secure should match isProduction=true")
	}
}

func TestNewStateAndSlugFromState(t *testing.T) {
	state, err := NewState("acme")
	if err != nil {
		t.Fatalf("NewState() error = %v", err)
	}
	if got := SlugFromState(state); got != "acme" {
		t.Errorf("SlugFromState() = %q, want acme", got)
	}

	// Two states for the same slug must differ (random component).
	state2, _ := NewState("acme")
	if state == state2 {
		t.Error("NewState() produced identical states on two calls")
	}
}

func TestSlugFromState_Malformed(t *testing.T) {
	if got := SlugFromState("not-a-valid-state"); got != "" {
		t.Errorf("SlugFromState() = %q, want empty for malformed input", got)
	}
	if got := SlugFromState("!!!.random"); got != "" {
		t.Errorf("SlugFromState() = %q, want empty for undecodable prefix", got)
	}
}

func TestStateMatches(t *testing.T) {
	if !StateMatches("abc", "abc") {
		t.Error("StateMatches() = false for identical strings")
	}
	if StateMatches("abc", "xyz") {
		t.Error("StateMatches() = true for different strings")
	}
}
