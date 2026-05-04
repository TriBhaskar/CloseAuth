package spring

import (
	"context"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// mockSpringClient implements a minimal SpringClient for testing TokenManager.
type mockTokenFetcher struct {
	callCount atomic.Int32
	delay     time.Duration
	failAfter int32 // fail if callCount > failAfter
}

func TestTokenManager_CachesToken(t *testing.T) {
	logger := slog.Default()
	tm := NewTokenManager(logger)

	// Create a mock - we'll override fetchAccessToken behavior via the client
	cfg := &Config{
		OAuth2ServerURL:     "http://mock:9088",
		ContextPath:         "/closeauth",
		DefaultClientID:     "test",
		DefaultClientSecret: "test",
		DefaultRedirectURL:  "http://localhost/callback",
		DefaultScope:        "client.create",
	}

	// Build a real client but we won't actually call Spring
	// Instead we test the TokenManager logic by setting token directly
	client := &SpringClient{
		config:       cfg,
		tokenManager: tm,
		logger:       logger,
	}
	tm.SetClient(client)

	// Manually set a valid token
	tm.mu.Lock()
	tm.currentToken = &AccessTokenResponse{
		AccessToken: "cached-token-123",
		ExpiresIn:   3600,
	}
	tm.expiresAt = time.Now().Add(1 * time.Hour)
	tm.mu.Unlock()

	// Should return cached token without network call
	token, err := tm.GetValidToken(context.Background())
	if err != nil {
		t.Fatalf("GetValidToken() error = %v", err)
	}
	if token != "cached-token-123" {
		t.Errorf("GetValidToken() = %q, want %q", token, "cached-token-123")
	}
}

func TestTokenManager_ExpiryBuffer(t *testing.T) {
	logger := slog.Default()
	tm := NewTokenManager(logger)

	// Set a token that expires in 20 seconds (within 30s buffer)
	tm.mu.Lock()
	tm.currentToken = &AccessTokenResponse{
		AccessToken: "almost-expired",
		ExpiresIn:   20,
	}
	tm.expiresAt = time.Now().Add(20 * time.Second)
	tm.mu.Unlock()

	// isValid should return false because 20s < 30s buffer
	tm.mu.RLock()
	valid := tm.isValid()
	tm.mu.RUnlock()

	if valid {
		t.Error("isValid() should return false for token expiring within 30s buffer")
	}
}

func TestTokenManager_InvalidateToken(t *testing.T) {
	logger := slog.Default()
	tm := NewTokenManager(logger)

	// Set a valid token
	tm.mu.Lock()
	tm.currentToken = &AccessTokenResponse{AccessToken: "valid", ExpiresIn: 3600}
	tm.expiresAt = time.Now().Add(1 * time.Hour)
	tm.mu.Unlock()

	// Invalidate
	tm.InvalidateToken()

	// Should be nil now
	tm.mu.RLock()
	if tm.currentToken != nil {
		t.Error("InvalidateToken() should set currentToken to nil")
	}
	tm.mu.RUnlock()
}

func TestTokenManager_ConcurrentAccess(t *testing.T) {
	logger := slog.Default()
	tm := NewTokenManager(logger)

	// Set a valid token
	tm.mu.Lock()
	tm.currentToken = &AccessTokenResponse{AccessToken: "concurrent-safe", ExpiresIn: 3600}
	tm.expiresAt = time.Now().Add(1 * time.Hour)
	tm.mu.Unlock()

	// Simulate 100 concurrent reads
	var wg sync.WaitGroup
	errCount := atomic.Int32{}

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			token, err := tm.GetValidToken(context.Background())
			if err != nil {
				errCount.Add(1)
				return
			}
			if token != "concurrent-safe" {
				errCount.Add(1)
			}
		}()
	}

	wg.Wait()

	if errCount.Load() > 0 {
		t.Errorf("concurrent access had %d errors", errCount.Load())
	}
}

func TestTokenManager_NilClient(t *testing.T) {
	logger := slog.Default()
	tm := NewTokenManager(logger)

	// Don't set client — should fail on refresh
	_, err := tm.GetValidToken(context.Background())
	if err == nil {
		t.Error("GetValidToken() should fail when client is nil")
	}
}
