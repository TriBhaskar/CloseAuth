package models

func DefaultClientTheme() ClientTheme {
	// Return a default theme configuration
	return ClientTheme{
		IsActive:        true,
		IsDefault:       true,
		DefaultMode:     stringPtr("system"),
		AllowModeToggle: true,
	}
}

func stringPtr(s string) *string {
	return &s
}
