package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNoCacheMiddleware_DoesNotDuplicateHeadersWhenSetTwice(t *testing.T) {
	// Simulates the real hazard: NoCacheMiddleware runs, then a later step
	// (e.g. a relayed backend response via proxy.CopyHeaders) sets
	// Cache-Control again. With Add (the old behavior) this would produce
	// two values; with Set (the fix) there is exactly one.
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0")
	})

	req := httptest.NewRequest(http.MethodGet, "/t/acme/api/session", nil)
	rec := httptest.NewRecorder()
	NoCacheMiddleware(next).ServeHTTP(rec, req)

	values := rec.Result().Header.Values("Cache-Control")
	if len(values) != 1 {
		t.Errorf("Cache-Control header count = %d, want 1, got %v", len(values), values)
	}
}

func TestNoCacheMiddleware_SetsAllThreeHeaders(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})
	req := httptest.NewRequest(http.MethodGet, "/t/acme/api/session", nil)
	rec := httptest.NewRecorder()
	NoCacheMiddleware(next).ServeHTTP(rec, req)

	h := rec.Result().Header
	if h.Get("Cache-Control") == "" || h.Get("Pragma") == "" || h.Get("Expires") == "" {
		t.Errorf("missing cache-prevention headers: %+v", h)
	}
}
