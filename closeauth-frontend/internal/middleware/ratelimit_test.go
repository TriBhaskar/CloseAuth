package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestIPRateLimiter_AllowsUpToTheLimit(t *testing.T) {
	l := NewIPRateLimiter(3, time.Minute)

	for i := 0; i < 3; i++ {
		if !l.Allow("1.2.3.4") {
			t.Fatalf("request %d: expected allowed, got blocked", i+1)
		}
	}
	if l.Allow("1.2.3.4") {
		t.Fatal("4th request within the window: expected blocked, got allowed")
	}
}

func TestIPRateLimiter_TracksEachIPIndependently(t *testing.T) {
	l := NewIPRateLimiter(1, time.Minute)

	if !l.Allow("1.1.1.1") {
		t.Fatal("first request from 1.1.1.1 should be allowed")
	}
	if l.Allow("1.1.1.1") {
		t.Fatal("second request from 1.1.1.1 should be blocked")
	}
	if !l.Allow("2.2.2.2") {
		t.Fatal("first request from a DIFFERENT ip should be allowed regardless of 1.1.1.1's state")
	}
}

func TestIPRateLimiter_AllowsAgainOnceOldHitsAgeOutOfTheWindow(t *testing.T) {
	l := NewIPRateLimiter(1, 10*time.Millisecond)

	if !l.Allow("1.2.3.4") {
		t.Fatal("first request should be allowed")
	}
	if l.Allow("1.2.3.4") {
		t.Fatal("immediate second request should be blocked")
	}

	time.Sleep(15 * time.Millisecond)

	if !l.Allow("1.2.3.4") {
		t.Fatal("request after the window elapsed should be allowed again")
	}
}

func TestIPRateLimiter_Middleware_CallsOnBlockedInsteadOfNextWhenOverLimit(t *testing.T) {
	l := NewIPRateLimiter(1, time.Minute)
	nextCalls := 0
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { nextCalls++ })
	blockedCalls := 0
	onBlocked := func(w http.ResponseWriter, r *http.Request) {
		blockedCalls++
		w.WriteHeader(http.StatusNotFound)
	}

	handler := l.Middleware(onBlocked)(next)

	req1 := httptest.NewRequest(http.MethodGet, "/api/entry/resolve", nil)
	req1.RemoteAddr = "9.9.9.9:12345"
	handler.ServeHTTP(httptest.NewRecorder(), req1)

	req2 := httptest.NewRequest(http.MethodGet, "/api/entry/resolve", nil)
	req2.RemoteAddr = "9.9.9.9:23456"
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)

	if nextCalls != 1 {
		t.Errorf("next called %d times, want 1 (only the first request)", nextCalls)
	}
	if blockedCalls != 1 {
		t.Errorf("onBlocked called %d times, want 1 (only the second request)", blockedCalls)
	}
	if rec2.Code != http.StatusNotFound {
		t.Errorf("blocked response status = %d, want 404", rec2.Code)
	}
}

func TestClientIP_StripsThePortFromRemoteAddr(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "203.0.113.7:54321"

	if got := clientIP(req); got != "203.0.113.7" {
		t.Errorf("clientIP() = %q, want %q", got, "203.0.113.7")
	}
}

func TestClientIP_HandlesIPv6WithBrackets(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "[::1]:54321"

	if got := clientIP(req); got != "::1" {
		t.Errorf("clientIP() = %q, want %q", got, "::1")
	}
}

func TestClientIP_FallsBackToRawRemoteAddrWhenNoPort(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "not-a-host-port"

	if got := clientIP(req); got != "not-a-host-port" {
		t.Errorf("clientIP() = %q, want the raw value back", got)
	}
}
