package handlers

import (
	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/handlers/oauth"
)

// Deprecated: OAuthClientAuthHandler is deprecated. Use handlers/oauth.OAuthClientAuthHandler instead.
// This handler provides client-specific themed login/registration pages.
type OAuthClientAuthHandler struct {
	*oauth.OAuthClientAuthHandler
}

// Deprecated: NewOAuthClientAuthHandler is deprecated. Use handlers/oauth.NewOAuthClientAuthHandler instead.
func NewOAuthClientAuthHandler(themeRepo *repository.ThemeRepository) *OAuthClientAuthHandler {
	return &OAuthClientAuthHandler{
		OAuthClientAuthHandler: oauth.NewOAuthClientAuthHandler(themeRepo),
	}
}
