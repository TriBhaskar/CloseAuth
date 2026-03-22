package handlers

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/handlers/response"
)

type ThemeHandler struct {
    themeRepo *repository.ThemeRepository
    logger    *slog.Logger
}

func NewThemeHandler(themeRepo *repository.ThemeRepository) *ThemeHandler {
    return &ThemeHandler{
        themeRepo: themeRepo,
        logger:    slog.Default().With("handler", "theme"),
    }
}

// HandleGetClientTheme retrieves default theme for a client
// Progressive enhancement: Works as JSON API, can be extended for HTMX fragments
func (h *ThemeHandler) HandleGetClientTheme(w http.ResponseWriter, r *http.Request) {
    clientID := r.URL.Query().Get("client_id")
    if clientID == "" {
        response.RenderJSONError(w, "client_id parameter required", http.StatusBadRequest)
        return
    }

    theme, err := h.themeRepo.FindDefaultTheme(r.Context(), clientID)
    if err != nil {
        response.RenderJSONError(w, "Theme not found for client", http.StatusNotFound)
        return
    }

    // Return JSON for API consumption
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    json.NewEncoder(w).Encode(theme)
}

// HandleGetClientThemes lists all available themes for a client
func (h *ThemeHandler) HandleGetClientThemes(w http.ResponseWriter, r *http.Request) {
    clientID := r.URL.Query().Get("client_id")
    if clientID == "" {
        response.RenderJSONError(w, "client_id parameter required", http.StatusBadRequest)
        return
    }

    themes, err := h.themeRepo.FindByClientID(r.Context(), clientID)
    if err != nil {
        response.RenderJSONError(w, "Failed to retrieve themes", http.StatusInternalServerError)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    json.NewEncoder(w).Encode(themes)
}

// HandleGetThemeWithConfig retrieves theme with extended configurations
func (h *ThemeHandler) HandleGetThemeWithConfig(w http.ResponseWriter, r *http.Request) {
    clientID := r.URL.Query().Get("client_id")
    themeName := r.URL.Query().Get("theme_name")
    
    if clientID == "" || themeName == "" {
        response.RenderJSONError(w, "client_id and theme_name parameters required", http.StatusBadRequest)
        return
    }

    themeWithConfig, err := h.themeRepo.FindThemeWithConfig(r.Context(), clientID, themeName)
    if err != nil {
        response.RenderJSONError(w, "Theme configuration not found", http.StatusNotFound)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    if err := json.NewEncoder(w).Encode(themeWithConfig); err != nil {
        h.logger.Error("failed to encode theme response", "error", err)
    }
}