package models

import "time"

// ClientTheme represents the client_themes table
type ClientTheme struct {
    ID           int       `db:"id" json:"id"`
    ClientID     string    `db:"client_id" json:"client_id"`
    ThemeName    string    `db:"theme_name" json:"theme_name"`
    IsActive     bool      `db:"is_active" json:"is_active"`
    IsDefault    bool      `db:"is_default" json:"is_default"`
    LogoURL      *string   `db:"logo_url" json:"logo_url,omitempty"`
    
    // Light mode colors
    LightPrimaryColor    *string `db:"light_primary_color" json:"light_primary_color,omitempty"`
    LightBackgroundColor *string `db:"light_background_color" json:"light_background_color,omitempty"`
    LightButtonColor     *string `db:"light_button_color" json:"light_button_color,omitempty"`
    LightTextColor       *string `db:"light_text_color" json:"light_text_color,omitempty"`
    
    // Dark mode colors
    DarkPrimaryColor     *string `db:"dark_primary_color" json:"dark_primary_color,omitempty"`
    DarkBackgroundColor  *string `db:"dark_background_color" json:"dark_background_color,omitempty"`
    DarkButtonColor      *string `db:"dark_button_color" json:"dark_button_color,omitempty"`
    DarkTextColor        *string `db:"dark_text_color" json:"dark_text_color,omitempty"`
    
    // User preferences
    DefaultMode      *string `db:"default_mode" json:"default_mode,omitempty"` // 'light', 'dark', 'system'
    AllowModeToggle  bool    `db:"allow_mode_toggle" json:"allow_mode_toggle"`
    
    CreatedAt time.Time `db:"created_at" json:"created_at"`
    UpdatedAt time.Time `db:"updated_at" json:"updated_at"`
}

// ThemeConfiguration represents the theme_configurations table
type ThemeConfiguration struct {
    ID          int       `db:"id" json:"id"`
    ThemeID     int       `db:"theme_id" json:"theme_id"`
    ConfigKey   string    `db:"config_key" json:"config_key"`
    ConfigValue string    `db:"config_value" json:"config_value"`
    ConfigType  string    `db:"config_type" json:"config_type"` // 'string', 'url', 'json', 'number', 'css'
    CreatedAt   time.Time `db:"created_at" json:"created_at"`
    UpdatedAt   time.Time `db:"updated_at" json:"updated_at"`
}

// ThemeColors is a helper struct for easier color management
type ThemeColors struct {
    Primary    string `json:"primary"`
    Background string `json:"background"`
    Button     string `json:"button"`
    Text       string `json:"text"`
}

// GetLightColors returns light mode colors as a structured object
func (ct *ClientTheme) GetLightColors() ThemeColors {
    return ThemeColors{
        Primary:    safeString(ct.LightPrimaryColor),
        Background: safeString(ct.LightBackgroundColor),
        Button:     safeString(ct.LightButtonColor),
        Text:       safeString(ct.LightTextColor),
    }
}

// GetDarkColors returns dark mode colors as a structured object
func (ct *ClientTheme) GetDarkColors() ThemeColors {
    return ThemeColors{
        Primary:    safeString(ct.DarkPrimaryColor),
        Background: safeString(ct.DarkBackgroundColor),
        Button:     safeString(ct.DarkButtonColor),
        Text:       safeString(ct.DarkTextColor),
    }
}

// GetDefaultMode returns the default mode with fallback
func (ct *ClientTheme) GetDefaultMode() string {
    if ct.DefaultMode != nil {
        return *ct.DefaultMode
    }
    return "light" // Default fallback
}

// Helper function to safely dereference string pointers
func safeString(s *string) string {
    if s != nil {
        return *s
    }
    return ""
}

// ClientThemeWithConfig combines theme and its configurations
type ClientThemeWithConfig struct {
    Theme          ClientTheme                    `json:"theme"`
    Configurations map[string]ThemeConfiguration  `json:"configurations,omitempty"`
}