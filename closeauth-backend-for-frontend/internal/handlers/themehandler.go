package handlers

import (
	"encoding/json"
	"net/http"

	"closeauth-backend-for-frontend/internal/database/repository"
	"closeauth-backend-for-frontend/internal/middleware"
)

type ThemeHandler struct {
    themeRepo *repository.ThemeRepository
}

func NewThemeHandler(themeRepo *repository.ThemeRepository) *ThemeHandler {
    return &ThemeHandler{
        themeRepo: themeRepo,
    }
}

// HandleGetClientTheme retrieves default theme for a client
// Progressive enhancement: Works as JSON API, can be extended for HTMX fragments
func (h *ThemeHandler) HandleGetClientTheme(w http.ResponseWriter, r *http.Request) {
    clientID := r.URL.Query().Get("client_id")
    if clientID == "" {
        h.handleError(w, r, "client_id parameter required", http.StatusBadRequest)
        return
    }

    theme, err := h.themeRepo.FindDefaultTheme(r.Context(), clientID)
    if err != nil {
        h.handleError(w, r, "Theme not found for client", http.StatusNotFound)
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
        h.handleError(w, r, "client_id parameter required", http.StatusBadRequest)
        return
    }

    themes, err := h.themeRepo.FindByClientID(r.Context(), clientID)
    if err != nil {
        h.handleError(w, r, "Failed to retrieve themes", http.StatusInternalServerError)
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
        h.handleError(w, r, "client_id and theme_name parameters required", http.StatusBadRequest)
        return
    }

    themeWithConfig, err := h.themeRepo.FindThemeWithConfig(r.Context(), clientID, themeName)
    if err != nil {
        h.handleError(w, r, "Theme configuration not found", http.StatusNotFound)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    json.NewEncoder(w).Encode(themeWithConfig)
}

// handleError provides dual-format error responses (HTMX vs standard)
// Follows CloseAuth BFF pattern from HTMX_BOOST_GUIDE.md
func (h *ThemeHandler) handleError(w http.ResponseWriter, r *http.Request, message string, code int) {
    if middleware.IsHTMXRequest(r) {
        // Return HTML fragment for HTMX requests
        w.Header().Set("Content-Type", "text/html")
        w.WriteHeader(code)
        w.Write([]byte(`<div class="error-message bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded">` + message + `</div>`))
    } else {
        // Return JSON for API requests
        w.Header().Set("Content-Type", "application/json")
        w.WriteHeader(code)
        json.NewEncoder(w).Encode(map[string]string{"error": message})
    }
}