package spring

// ──────────────────────────────────────────────────────────────────────────────
// BFF Config Sync — fetched from Spring at startup
// ──────────────────────────────────────────────────────────────────────────────

// BffConfigResponse matches the JSON from GET /closeauth/bff/config on Spring.
type BffConfigResponse struct {
	Version      BffVersionInfo      `json:"version"`
	Session      BffSessionConfig    `json:"session"`
	Security     BffSecurityConfig   `json:"security"`
	OTP          BffOtpConfig        `json:"otp"`
	Endpoints    BffEndpointsConfig  `json:"endpoints"`
	Registration BffRegistrationConfig `json:"registration"`
	Features     BffFeaturesConfig   `json:"features"`
}

type BffVersionInfo struct {
	API           string `json:"api"`
	Server        string `json:"server"`
	MinBffVersion string `json:"minBffVersion"`
}

type BffSessionConfig struct {
	TimeoutSeconds         int `json:"timeoutSeconds"`
	OAuthContextTTLSeconds int `json:"oauthContextTtlSeconds"`
}

type BffSecurityConfig struct {
	MaxLoginAttempts       int `json:"maxLoginAttempts"`
	LockoutDurationMinutes int `json:"lockoutDurationMinutes"`
}

type BffOtpConfig struct {
	Length          int   `json:"length"`
	ValiditySeconds int64 `json:"validitySeconds"`
	ResendRateLimit int   `json:"resendRateLimit"`
}

type BffEndpointsConfig struct {
	LoginProcessingURL string `json:"loginProcessingUrl"`
	ConsentSubmitURL   string `json:"consentSubmitUrl"`
	ContextPath        string `json:"contextPath"`
	APIPrefix          string `json:"apiPrefix"`
	ClientInfoURL      string `json:"clientInfoUrl"`
	RegisterUserURL    string `json:"registerUserUrl"`
	AdminAuthBase      string `json:"adminAuthBase"`
	ClientConfigBase   string `json:"clientConfigBase"`
}

type BffRegistrationConfig struct {
	CacheTTLHours      int `json:"cacheTtlHours"`
	AdminPendingTTLDays int `json:"adminPendingTtlDays"`
}

type BffFeaturesConfig struct {
	DynamicClientRegistration bool `json:"dynamicClientRegistration"`
}

// ──────────────────────────────────────────────────────────────────────────────
// OIDC Discovery — fetched from /.well-known/openid-configuration
// ──────────────────────────────────────────────────────────────────────────────

// OIDCDiscovery represents the standard OpenID Connect Discovery response.
type OIDCDiscovery struct {
	Issuer                string   `json:"issuer"`
	AuthorizationEndpoint string   `json:"authorization_endpoint"`
	TokenEndpoint         string   `json:"token_endpoint"`
	UserinfoEndpoint      string   `json:"userinfo_endpoint"`
	JwksURI               string   `json:"jwks_uri"`
	RegistrationEndpoint  string   `json:"registration_endpoint"`
	IntrospectionEndpoint string   `json:"introspection_endpoint"`
	RevocationEndpoint    string   `json:"revocation_endpoint"`
	EndSessionEndpoint    string   `json:"end_session_endpoint"`
	ScopesSupported       []string `json:"scopes_supported"`
	ResponseTypesSupported []string `json:"response_types_supported"`
	GrantTypesSupported   []string `json:"grant_types_supported"`
}

// ──────────────────────────────────────────────────────────────────────────────
// DiscoveredConfig — merged result of both discovery calls
// ──────────────────────────────────────────────────────────────────────────────

// DiscoveredConfig holds all configuration fetched from Spring at startup.
// The BFF's Config methods check these values first, falling back to
// environment-variable defaults if discovery was not available.
type DiscoveredConfig struct {
	// Whether discovery succeeded (both endpoints responded)
	Available bool

	// From /closeauth/bff/config
	BffConfig *BffConfigResponse

	// From /.well-known/openid-configuration
	OIDC *OIDCDiscovery
}

