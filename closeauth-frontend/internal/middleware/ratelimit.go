package middleware

import (
	"net"
	"net/http"
	"sync"
	"time"
)

// IPRateLimiter is a simple in-memory, per-IP fixed-window rate limiter — the
// FIRST piece of shared, in-process mutable state in this BFF. Everything
// else here is either client-side cookie state or a stateless proxy hop (see
// server.go's "no direct database access, ever" comment) — a single limiter
// doesn't justify introducing a whole new datastore dependency into an
// otherwise datastore-free process, so this stays in-memory rather than
// reaching for Redis. Single-instance only by construction: if the BFF is
// ever horizontally scaled, this needs to move to a shared store (Redis,
// most likely, matching the backend's own RedisRateLimiter shape).
type IPRateLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	limit  int
	window time.Duration
}

// NewIPRateLimiter returns a limiter allowing at most limit requests per IP
// within window.
func NewIPRateLimiter(limit int, window time.Duration) *IPRateLimiter {
	return &IPRateLimiter{
		hits:   make(map[string][]time.Time),
		limit:  limit,
		window: window,
	}
}

// Allow reports whether a request from ip is within the limit, recording it
// if so. Timestamps older than window are pruned on every call, so an IP
// that stops sending traffic doesn't grow the map forever.
func (l *IPRateLimiter) Allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	cutoff := time.Now().Add(-l.window)

	var live []time.Time
	for _, t := range l.hits[ip] {
		if t.After(cutoff) {
			live = append(live, t)
		}
	}

	if len(live) >= l.limit {
		l.hits[ip] = live
		return false
	}

	l.hits[ip] = append(live, time.Now())
	return true
}

// Middleware returns chi-compatible middleware: onBlocked runs instead of
// next when the caller's IP is over the limit. Taking onBlocked as a
// parameter (rather than a fixed status/body this package decides) lets each
// route pick its own blocked-response shape — e.g. /api/entry/resolve must
// respond byte-identically to an unknown-tenant lookup (see
// handlers_entry_proxy.go), which is a route-specific security requirement,
// not something a generic rate-limit middleware should hardcode.
func (l *IPRateLimiter) Middleware(onBlocked http.HandlerFunc) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !l.Allow(clientIP(r)) {
				onBlocked(w, r)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// clientIP reads the caller's IP off r.RemoteAddr, which chi's own RealIP
// middleware (registered ahead of this one in routes.go) already normalizes
// from X-Forwarded-For/X-Real-IP — this function doesn't re-parse those
// headers itself, so there's exactly one place in the process that decides
// what "the caller's IP" means.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
