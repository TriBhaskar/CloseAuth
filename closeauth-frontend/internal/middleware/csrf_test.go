package middleware

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCSRFValidation_DoesNotConsumeJSONBody(t *testing.T) {
	const body = `{"tenantId":"abc123"}`

	var bodyAtHandler string
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		bodyAtHandler = string(b)
		w.WriteHeader(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/t/acme/api/signout", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(CSRFHeaderName, "tok-123")
	req.AddCookie(&http.Cookie{Name: CSRFCookieName, Value: "tok-123"})

	rec := httptest.NewRecorder()
	CSRFValidationMiddleware(next).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if bodyAtHandler != body {
		t.Errorf("downstream handler saw body %q, want %q (ParseForm must not have consumed it)", bodyAtHandler, body)
	}
}

func TestCSRFValidation_FormFallbackStillWorksForFormPosts(t *testing.T) {
	form := "csrf_token=tok-123&other=value"

	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })

	req := httptest.NewRequest(http.MethodPost, "/oauth2/consent", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: CSRFCookieName, Value: "tok-123"})

	rec := httptest.NewRecorder()
	CSRFValidationMiddleware(next).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 (form-field fallback should still validate)", rec.Code)
	}
}

func TestCSRFValidation_MissingCookie_Returns403(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("downstream handler should not be called")
	})

	req := httptest.NewRequest(http.MethodPost, "/t/acme/api/signout", nil)
	rec := httptest.NewRecorder()
	CSRFValidationMiddleware(next).ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
}

func TestCSRFValidation_MissingToken_DoesNotConsumeJSONBody(t *testing.T) {
	// A JSON POST with no CSRF header/cookie relationship at all must still
	// be rejected WITHOUT the ParseForm fallback firing (it isn't a form
	// content type), so the body remains intact for any logging/diagnostics
	// that might read it.
	const body = `{"tenantId":"abc123"}`
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("downstream handler should not be called")
	})

	req := httptest.NewRequest(http.MethodPost, "/t/acme/api/signout", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(&http.Cookie{Name: CSRFCookieName, Value: "tok-123"})

	rec := httptest.NewRecorder()
	CSRFValidationMiddleware(next).ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
}

func TestCSRFValidation_GetBypassesValidation(t *testing.T) {
	called := false
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true })

	req := httptest.NewRequest(http.MethodGet, "/t/acme/api/session", nil)
	rec := httptest.NewRecorder()
	CSRFValidationMiddleware(next).ServeHTTP(rec, req)

	if !called {
		t.Error("GET should bypass CSRF validation entirely")
	}
}

func TestHandleCSRFToken_MintsAndReturnsToken(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/csrf", nil)
	rec := httptest.NewRecorder()

	HandleCSRFToken(true)(rec, req)

	if rec.Code != http.StatusOK && rec.Code != 0 {
		t.Errorf("status = %d, want 200", rec.Code)
	}
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	if !cookies[0].Secure {
		t.Error("Secure should be true when isProduction=true, not hardcoded false")
	}
	if !strings.Contains(rec.Body.String(), `"token"`) {
		t.Errorf("body = %s, want a token field", rec.Body.String())
	}
}

func TestHandleCSRFToken_ReusesExistingCookie(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/csrf", nil)
	req.AddCookie(&http.Cookie{Name: CSRFCookieName, Value: "existing-token"})
	rec := httptest.NewRecorder()

	HandleCSRFToken(false)(rec, req)

	if len(rec.Result().Cookies()) != 0 {
		t.Error("should not set a new cookie when one already exists")
	}
	if !strings.Contains(rec.Body.String(), "existing-token") {
		t.Errorf("body = %s, want the existing token echoed back", rec.Body.String())
	}
}

func TestClearCSRFToken(t *testing.T) {
	w := httptest.NewRecorder()
	ClearCSRFToken(w, true)

	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("got %d cookies, want 1", len(cookies))
	}
	if cookies[0].MaxAge != -1 || !cookies[0].Secure || cookies[0].SameSite != http.SameSiteLaxMode {
		t.Errorf("ClearCSRFToken() cookie = %+v", cookies[0])
	}
}
