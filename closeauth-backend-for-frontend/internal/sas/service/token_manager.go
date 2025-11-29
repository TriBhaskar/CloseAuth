package service

import (
	"closeauth-backend-for-frontend/internal/sas/client"
	"closeauth-backend-for-frontend/internal/sas/config"
	"closeauth-backend-for-frontend/internal/sas/model"
	"context"
	"log/slog"
	"sync"
	"time"
)

// TokenManager handles OAuth2 access token lifecycle with automatic refresh
type TokenManager struct {
	client       *client.OAuth2Client
	logger       *slog.Logger
	mu           sync.RWMutex
	currentToken *model.AccessTokenResponse
	expiresAt    time.Time
}

// NewTokenManager creates a new token manager instance
func NewTokenManager(cfg *config.OAuthClientConfig) *TokenManager {
	return &TokenManager{
		client: client.NewOAuth2Client(cfg),
		logger: slog.Default().With("component", "token_manager"),
	}
}

// GetValidToken returns a valid access token, refreshing if necessary
// This method is thread-safe and will only fetch a new token if the current one
// is expired or about to expire (within 30 seconds buffer)
func (tm *TokenManager) GetValidToken(ctx context.Context) (string, error) {
	tm.mu.RLock()
	// Check if we have a valid token with at least 30 seconds before expiry
	if tm.currentToken != nil && time.Now().Add(30*time.Second).Before(tm.expiresAt) {
		token := tm.currentToken.AccessToken
		tm.mu.RUnlock()
		tm.logger.Debug("Using cached access token",
			"expires_in", time.Until(tm.expiresAt).Seconds())
		return token, nil
	}
	tm.mu.RUnlock()

	// Need to fetch a new token
	return tm.refreshToken(ctx)
}

// refreshToken fetches a new access token from the OAuth2 server
func (tm *TokenManager) refreshToken(ctx context.Context) (string, error) {
	tm.mu.Lock()
	defer tm.mu.Unlock()

	// Double-check: another goroutine might have refreshed while we were waiting for the lock
	if tm.currentToken != nil && time.Now().Add(30*time.Second).Before(tm.expiresAt) {
		tm.logger.Debug("Token was refreshed by another goroutine")
		return tm.currentToken.AccessToken, nil
	}

	tm.logger.Info("Fetching new access token")
	startTime := time.Now()

	tokenResp, err := tm.client.GetAccessTokenWithContext(ctx)
	if err != nil {
		tm.logger.Error("Failed to fetch access token", "error", err)
		return "", err
	}

	// Calculate expiration time with a small buffer
	// If ExpiresIn is 300 seconds, we consider it expired at 299 seconds
	expiresIn := time.Duration(tokenResp.ExpiresIn) * time.Second
	tm.expiresAt = time.Now().Add(expiresIn)

	tm.currentToken = tokenResp
	
	tm.logger.Info("Access token fetched successfully",
		"expires_in", tokenResp.ExpiresIn,
		"expires_at", tm.expiresAt.Format(time.RFC3339),
		"fetch_duration_ms", time.Since(startTime).Milliseconds())

	return tokenResp.AccessToken, nil
}

// InvalidateToken forces the next GetValidToken call to fetch a fresh token
// Useful when a 401 response is received
func (tm *TokenManager) InvalidateToken() {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	
	tm.logger.Info("Invalidating current access token")
	tm.currentToken = nil
	tm.expiresAt = time.Time{}
}

// GetTokenInfo returns information about the current token (for debugging)
func (tm *TokenManager) GetTokenInfo() (hasToken bool, expiresAt time.Time, expiresIn time.Duration) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	
	if tm.currentToken == nil {
		return false, time.Time{}, 0
	}
	
	return true, tm.expiresAt, time.Until(tm.expiresAt)
}
