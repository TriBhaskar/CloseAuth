package repository

import (
	"context"
	"database/sql"
	"fmt"

	"closeauth-backend-for-frontend/internal/database"
	"closeauth-backend-for-frontend/internal/database/models"
)

type ThemeRepository struct {
    db *database.Database
}

func NewThemeRepository(db *database.Database) *ThemeRepository {
    return &ThemeRepository{db: db}
}

// FindByClientID retrieves all active themes for a client
func (r *ThemeRepository) FindByClientID(ctx context.Context, clientID string) ([]models.ClientTheme, error) {
    var themes []models.ClientTheme
    query := `
        SELECT id, client_id, theme_name, is_active, is_default, logo_url,
               light_primary_color, light_background_color, light_button_color, light_text_color,
               dark_primary_color, dark_background_color, dark_button_color, dark_text_color,
               default_mode, allow_mode_toggle, created_at, updated_at
        FROM client_themes
        WHERE client_id = $1 AND is_active = true
        ORDER BY is_default DESC, theme_name ASC
    `
    
    err := r.db.SelectContext(ctx, &themes, query, clientID)
    if err != nil {
        return nil, fmt.Errorf("failed to find themes for client %s: %w", clientID, err)
    }
    
    return themes, nil
}

// FindDefaultTheme retrieves the default theme for a client
func (r *ThemeRepository) FindDefaultTheme(ctx context.Context, clientID string) (*models.ClientTheme, error) {
    var theme models.ClientTheme
    query := `
        SELECT id, client_id, theme_name, is_active, is_default, logo_url,
               light_primary_color, light_background_color, light_button_color, light_text_color,
               dark_primary_color, dark_background_color, dark_button_color, dark_text_color,
               default_mode, allow_mode_toggle, created_at, updated_at
        FROM client_themes
        WHERE client_id = $1 AND is_active = true AND is_default = true
        LIMIT 1
    `
    
    err := r.db.GetContext(ctx, &theme, query, clientID)
    if err == sql.ErrNoRows {
        return nil, fmt.Errorf("no default theme found for client %s", clientID)
    }
    if err != nil {
        return nil, fmt.Errorf("failed to find default theme: %w", err)
    }
    
    return &theme, nil
}

// FindByClientIDAndName retrieves a specific theme by name
func (r *ThemeRepository) FindByClientIDAndName(ctx context.Context, clientID, themeName string) (*models.ClientTheme, error) {
    var theme models.ClientTheme
    query := `
        SELECT id, client_id, theme_name, is_active, is_default, logo_url,
               light_primary_color, light_background_color, light_button_color, light_text_color,
               dark_primary_color, dark_background_color, dark_button_color, dark_text_color,
               default_mode, allow_mode_toggle, created_at, updated_at
        FROM client_themes
        WHERE client_id = $1 AND theme_name = $2 AND is_active = true
    `
    
    err := r.db.GetContext(ctx, &theme, query, clientID, themeName)
    if err == sql.ErrNoRows {
        return nil, fmt.Errorf("theme '%s' not found for client %s", themeName, clientID)
    }
    if err != nil {
        return nil, fmt.Errorf("failed to find theme: %w", err)
    }
    
    return &theme, nil
}

// FindThemeByID retrieves a theme by its ID
func (r *ThemeRepository) FindThemeByID(ctx context.Context, themeID int) (*models.ClientTheme, error) {
    var theme models.ClientTheme
    query := `
        SELECT id, client_id, theme_name, is_active, is_default, logo_url,
               light_primary_color, light_background_color, light_button_color, light_text_color,
               dark_primary_color, dark_background_color, dark_button_color, dark_text_color,
               default_mode, allow_mode_toggle, created_at, updated_at
        FROM client_themes
        WHERE id = $1
    `
    
    err := r.db.GetContext(ctx, &theme, query, themeID)
    if err == sql.ErrNoRows {
        return nil, fmt.Errorf("theme with ID %d not found", themeID)
    }
    if err != nil {
        return nil, fmt.Errorf("failed to find theme: %w", err)
    }
    
    return &theme, nil
}

// FindConfigurationsByThemeID retrieves all configurations for a theme
func (r *ThemeRepository) FindConfigurationsByThemeID(ctx context.Context, themeID int) ([]models.ThemeConfiguration, error) {
    var configs []models.ThemeConfiguration
    query := `
        SELECT id, theme_id, config_key, config_value, config_type, created_at, updated_at
        FROM theme_configurations
        WHERE theme_id = $1
        ORDER BY config_key ASC
    `
    
    err := r.db.SelectContext(ctx, &configs, query, themeID)
    if err != nil {
        return nil, fmt.Errorf("failed to find configurations for theme %d: %w", themeID, err)
    }
    
    return configs, nil
}

// FindThemeWithConfig retrieves a theme with all its configurations
func (r *ThemeRepository) FindThemeWithConfig(ctx context.Context, clientID, themeName string) (*models.ClientThemeWithConfig, error) {
    // Get the theme
    theme, err := r.FindByClientIDAndName(ctx, clientID, themeName)
    if err != nil {
        return nil, err
    }
    
    // Get configurations
    configs, err := r.FindConfigurationsByThemeID(ctx, theme.ID)
    if err != nil {
        return nil, err
    }
    
    // Build configuration map for easier access
    configMap := make(map[string]models.ThemeConfiguration)
    for _, config := range configs {
        configMap[config.ConfigKey] = config
    }
    
    return &models.ClientThemeWithConfig{
        Theme:          *theme,
        Configurations: configMap,
    }, nil
}

// GetConfigValue retrieves a specific configuration value
func (r *ThemeRepository) GetConfigValue(ctx context.Context, themeID int, configKey string) (*models.ThemeConfiguration, error) {
    var config models.ThemeConfiguration
    query := `
        SELECT id, theme_id, config_key, config_value, config_type, created_at, updated_at
        FROM theme_configurations
        WHERE theme_id = $1 AND config_key = $2
    `
    
    err := r.db.GetContext(ctx, &config, query, themeID, configKey)
    if err == sql.ErrNoRows {
        return nil, fmt.Errorf("configuration '%s' not found for theme %d", configKey, themeID)
    }
    if err != nil {
        return nil, fmt.Errorf("failed to get configuration: %w", err)
    }
    
    return &config, nil
}

// ListAllThemes retrieves all active themes (useful for admin dashboard)
func (r *ThemeRepository) ListAllThemes(ctx context.Context, limit, offset int) ([]models.ClientTheme, error) {
    var themes []models.ClientTheme
    query := `
        SELECT id, client_id, theme_name, is_active, is_default, logo_url,
               light_primary_color, light_background_color, light_button_color, light_text_color,
               dark_primary_color, dark_background_color, dark_button_color, dark_text_color,
               default_mode, allow_mode_toggle, created_at, updated_at
        FROM client_themes
        WHERE is_active = true
        ORDER BY client_id ASC, is_default DESC, theme_name ASC
        LIMIT $1 OFFSET $2
    `
    
    err := r.db.SelectContext(ctx, &themes, query, limit, offset)
    if err != nil {
        return nil, fmt.Errorf("failed to list themes: %w", err)
    }
    
    return themes, nil
}

// CountThemesByClientID counts total themes for a client
func (r *ThemeRepository) CountThemesByClientID(ctx context.Context, clientID string) (int, error) {
    var count int
    query := `SELECT COUNT(*) FROM client_themes WHERE client_id = $1 AND is_active = true`
    
    err := r.db.GetContext(ctx, &count, query, clientID)
    if err != nil {
        return 0, fmt.Errorf("failed to count themes: %w", err)
    }
    
    return count, nil
}