package spring

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

// TokenManager handles OAuth2 access token lifecycle with automatic refresh.
// It is safe for concurrent use by multiple goroutines.
type TokenManager struct {
	client       *SpringClient
	logger       *slog.Logger
	mu           sync.RWMutex
	currentToken *AccessTokenResponse
	expiresAt    time.Time
}

// NewTokenManager creates a new token manager.
// The SpringClient is set after construction to break circular dependency.
func NewTokenManager(logger *slog.Logger) *TokenManager {
	return &TokenManager{
		logger: logger.With("component", "token_manager"),
	}
}

// SetClient sets the SpringClient reference (called after both are constructed).
func (tm *TokenManager) SetClient(client *SpringClient) {
	tm.client = client
}

// GetValidToken returns a valid access token string, refreshing if necessary.
// Uses double-check locking to avoid thundering herd on token refresh.
func (tm *TokenManager) GetValidToken(ctx context.Context) (string, error) {
	// Fast path: check with read lock
	tm.mu.RLock()
	if tm.isValid() {
		token := tm.currentToken.AccessToken
		tm.mu.RUnlock()
		tm.logger.Debug("using cached access token", "expires_in_seconds", time.Until(tm.expiresAt).Seconds())
		return token, nil
	}
	tm.mu.RUnlock()

	// Slow path: acquire write lock and refresh
	return tm.refreshToken(ctx)
}

// InvalidateToken forces the next GetValidToken call to fetch a fresh token.
func (tm *TokenManager) InvalidateToken() {
	tm.mu.Lock()
	defer tm.mu.Unlock()

	tm.logger.Info("invalidating current access token")
	tm.currentToken = nil
	tm.expiresAt = time.Time{}
}

// isValid checks if the current token is valid with a 30-second safety buffer.
// Must be called with at least a read lock held.
func (tm *TokenManager) isValid() bool {
	return tm.currentToken != nil && time.Now().Add(30*time.Second).Before(tm.expiresAt)
}

// refreshToken fetches a new access token from Spring.
func (tm *TokenManager) refreshToken(ctx context.Context) (string, error) {
	tm.mu.Lock()
	defer tm.mu.Unlock()

	// Double-check: another goroutine may have refreshed while we waited
	if tm.isValid() {
		tm.logger.Debug("token refreshed by another goroutine")
		return tm.currentToken.AccessToken, nil
	}

	if tm.client == nil {
		return "", fmt.Errorf("spring client not set on token manager")
	}

	tm.logger.Info("fetching new access token")
	start := time.Now()

	tokenResp, err := tm.client.fetchAccessToken(ctx)
	if err != nil {
		tm.logger.Error("failed to fetch access token", "error", err, "duration_ms", time.Since(start).Milliseconds())
		return "", fmt.Errorf("failed to fetch access token: %w", err)
	}

	tm.currentToken = tokenResp
	tm.expiresAt = time.Now().Add(time.Duration(tokenResp.ExpiresIn) * time.Second)

	tm.logger.Info("access token fetched successfully",
		"expires_in", tokenResp.ExpiresIn,
		"expires_at", tm.expiresAt.Format(time.RFC3339),
		"duration_ms", time.Since(start).Milliseconds(),
	)

	return tokenResp.AccessToken, nil
}
