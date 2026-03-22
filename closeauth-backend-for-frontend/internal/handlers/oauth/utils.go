package oauth

import (
	"net/http"
	"os"

	"closeauth-backend-for-frontend/internal/database/models"
	tmodels "closeauth-backend-for-frontend/internal/templates/models"
)

// getClientTheme fetches the theme for a given client ID
func (h *OAuthClientAuthHandler) getClientTheme(r *http.Request, clientID string) models.ClientTheme {
	if clientID == "" {
		return models.DefaultClientTheme()
	}
	theme, err := h.themeRepo.FindDefaultTheme(r.Context(), clientID)
	if err != nil {
		h.logger.Warn("failed to get theme for client, using default", "client_id", clientID, "error", err)
		return models.DefaultClientTheme()
	}
	return *theme
}

// isProduction checks if the application is running in a production environment
func isProduction() bool {
	return os.Getenv("APP_ENV") == "production"
}

// safeStringPtr safely dereferences a string pointer, returning empty string if nil
func safeStringPtr(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func convertThemeToThemeData(theme models.ClientTheme) tmodels.ThemeData {
	return tmodels.ThemeData{
		ClientID:        theme.ClientID,
		ThemeName:       theme.ThemeName,
		LogoURL:         safeStringPtr(theme.LogoURL),
		AllowModeToggle: theme.AllowModeToggle,
		DefaultMode:     safeStringPtr(theme.DefaultMode),
		LightColors: tmodels.ThemeColors{
			Primary:    safeStringPtr(theme.LightPrimaryColor),
			Background: safeStringPtr(theme.LightBackgroundColor),
			Button:     safeStringPtr(theme.LightButtonColor),
			Text:       safeStringPtr(theme.LightTextColor),
		},
		DarkColors: tmodels.ThemeColors{
			Primary:    safeStringPtr(theme.DarkPrimaryColor),
			Background: safeStringPtr(theme.DarkBackgroundColor),
			Button:     safeStringPtr(theme.DarkButtonColor),
			Text:       safeStringPtr(theme.DarkTextColor),
		},
	}
}
