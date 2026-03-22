package templates

// ScopeDisplay represents how a scope should be displayed in the consent page
type ScopeDisplay struct {
	Scope       string // Original scope name
	Title       string // Human-readable title
	Description string // Detailed description
	Icon        string // Icon identifier (profile, email, calendar, etc.)
}

// scopeDescriptions maps OAuth scopes to their display information
var scopeDescriptions = map[string]ScopeDisplay{
	"openid": {
		Scope:       "openid",
		Title:       "View your profile",
		Description: "Access your name, profile picture, and basic account info",
		Icon:        "profile",
	},
	"profile": {
		Scope:       "profile",
		Title:       "View your profile",
		Description: "Access your name, profile picture, and basic account info",
		Icon:        "profile",
	},
	"email": {
		Scope:       "email",
		Title:       "View your email address",
		Description: "Access your primary email address",
		Icon:        "email",
	},
	"address": {
		Scope:       "address",
		Title:       "View your address",
		Description: "Access your physical address",
		Icon:        "address",
	},
	"phone": {
		Scope:       "phone",
		Title:       "View your phone number",
		Description: "Access your phone number",
		Icon:        "phone",
	},
	"offline_access": {
		Scope:       "offline_access",
		Title:       "Maintain access",
		Description: "Stay signed in and access your data when you're not using the app",
		Icon:        "refresh",
	},
	"calendar": {
		Scope:       "calendar",
		Title:       "Manage your calendar",
		Description: "Read and write events to your calendar",
		Icon:        "calendar",
	},
	"read": {
		Scope:       "read",
		Title:       "Read access",
		Description: "Read your data",
		Icon:        "read",
	},
	"write": {
		Scope:       "write",
		Title:       "Write access",
		Description: "Modify your data",
		Icon:        "write",
	},
}

// MapScopesToDisplay converts a list of scope strings to their display representations
func MapScopesToDisplay(scopes []string) []ScopeDisplay {
	result := make([]ScopeDisplay, 0, len(scopes))
	
	for _, scope := range scopes {
		if display, ok := scopeDescriptions[scope]; ok {
			result = append(result, display)
		} else {
			// Fallback for unknown scopes - use the scope name as-is
			result = append(result, ScopeDisplay{
				Scope:       scope,
				Title:       formatScopeName(scope),
				Description: "Access to " + scope,
				Icon:        "default",
			})
		}
	}
	
	return result
}

// formatScopeName converts a scope name like "read_user" to "Read user"
func formatScopeName(scope string) string {
	if scope == "" {
		return "Unknown scope"
	}
	// Simple formatting: capitalize first letter
	runes := []rune(scope)
	if len(runes) > 0 && runes[0] >= 'a' && runes[0] <= 'z' {
		runes[0] = runes[0] - 32 // Convert to uppercase
	}
	return string(runes)
}
