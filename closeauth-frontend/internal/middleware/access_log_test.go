package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestAccessLogger_CallsNextForBothAssetAndNonAssetPaths proves the
// asset-skip logic doesn't accidentally break routing either way — an
// /assets/* request and an ordinary request both still reach the wrapped
// handler and get their response. What differs (chi's logger wrapping) is a
// pure log-volume concern, not something this test asserts on output for.
func TestAccessLogger_CallsNextForBothAssetAndNonAssetPaths(t *testing.T) {
	cases := []string{"/assets/index-abc123.js", "/assets/logo.svg", "/t/acme/console", "/api/health"}

	for _, path := range cases {
		called := false
		h := AccessLogger(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			called = true
			w.WriteHeader(http.StatusOK)
		}))

		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)

		if !called {
			t.Errorf("path %q: downstream handler was not called", path)
		}
		if rec.Code != http.StatusOK {
			t.Errorf("path %q: status = %d, want 200", path, rec.Code)
		}
	}
}
