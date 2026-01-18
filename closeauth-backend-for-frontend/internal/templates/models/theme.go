package models

// ThemeColors defines the color palette for a theme
type ThemeColors struct {
	Primary    string `json:"primary"`
	Background string `json:"background"`
	Button     string `json:"button"`
	Text       string `json:"text"`
}

// ThemeData holds all theme-related information for rendering
type ThemeData struct {
	ClientID        string      `json:"client_id"`
	ThemeName       string      `json:"theme_name"`
	LogoURL         string      `json:"logo_url"`
	AllowModeToggle bool        `json:"allow_mode_toggle"`
	DefaultMode     string      `json:"default_mode"`
	LightColors     ThemeColors `json:"light_colors"`
	DarkColors      ThemeColors `json:"dark_colors"`
}
