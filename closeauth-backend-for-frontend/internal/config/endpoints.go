package config

import (
	"fmt"
	"os"

	_ "github.com/joho/godotenv/autoload"
)

// EndpointsConfig holds all CloseAuth API endpoint configurations
type EndpointsConfig struct {
	OAuth2ServerURL string
	OAuth2          OAuth2Endpoints
	CloseAuth       CloseAuthEndpoints
}

// OAuth2Endpoints holds OAuth2-related endpoints
type OAuth2Endpoints struct {
	BaseURL      string // Full base URL with context path
	Token        string // /oauth2/token
	Authorize    string // /oauth2/authorize
	Introspect   string // /oauth2/introspect
	Revocation   string // /oauth2/revoke
	JWKS         string // /oauth2/jwks
	RegisterClient string // /connect/register
}

// CloseAuthEndpoints holds CloseAuth admin API endpoints
type CloseAuthEndpoints struct {
	AdminLogin              string // /api/v1/admin/auth/login
	AdminRegister           string // /api/v1/admin/auth/register
	AdminVerifyEmail        string // /api/v1/admin/auth/verify-email
	AdminResendOTP          string // /api/v1/admin/auth/resend-otp
	AdminForgotPassword     string // /api/v1/admin/auth/forgot-password
	AdminPasswordResetRequest string // /api/v1/admin/auth/reset-password
}

// LoadEndpointsConfig loads endpoint configuration from environment variables
func LoadEndpointsConfig() (*EndpointsConfig, error) {
	oauth2ServerURL := os.Getenv("OAUTH2_SERVER_URL")
	if oauth2ServerURL == "" {
		return nil, fmt.Errorf("OAUTH2_SERVER_URL is required")
	}

	oauth2ContextPath := os.Getenv("OAUTH2_API_CONTEXT_PATH")
	if oauth2ContextPath == "" {
		// Default to server URL + /closeauth
		oauth2ContextPath = oauth2ServerURL + "/closeauth"
	}

	return &EndpointsConfig{
		OAuth2ServerURL: oauth2ServerURL,
		OAuth2: OAuth2Endpoints{
			BaseURL:        oauth2ContextPath,
			Token:          getEnvOrDefault("OAUTH2_TOKEN_URL", "/oauth2/token"),
			Authorize:      getEnvOrDefault("OAUTH2_AUTHORIZE_URL", "/oauth2/authorize"),
			Introspect:     getEnvOrDefault("OAUTH2_INTROSPECT_URL", "/oauth2/introspect"),
			Revocation:     getEnvOrDefault("OAUTH2_REVOCATION_URL", "/oauth2/revoke"),
			JWKS:           getEnvOrDefault("OAUTH2_JWKS_URL", "/oauth2/jwks"),
			RegisterClient: getEnvOrDefault("OAUTH2_REGISTER_CLIENT_URL", "/connect/register"),
		},
		CloseAuth: CloseAuthEndpoints{
			AdminLogin:              getEnvOrDefault("CLOSEAUTH_ADMIN_LOGIN_URL", "/api/v1/admin/auth/login"),
			AdminRegister:           getEnvOrDefault("CLOSEAUTH_ADMIN_REGISTER_URL", "/api/v1/admin/auth/register"),
			AdminVerifyEmail:        getEnvOrDefault("CLOSEAUTH_ADMIN_VERIFY_EMAIL_URL", "/api/v1/admin/auth/verify-email"),
			AdminResendOTP:          getEnvOrDefault("CLOSEAUTH_ADMIN_RESEND_OTP_URL", "/api/v1/admin/auth/resend-otp"),
			AdminForgotPassword:     getEnvOrDefault("CLOSEAUTH_ADMIN_FORGOT_PASSWORD_URL", "/api/v1/admin/auth/forgot-password"),
			AdminPasswordResetRequest: getEnvOrDefault("CLOSEAUTH_ADMIN_PASSWORD_RESET_REQUEST_URL", "/api/v1/admin/auth/reset-password"),
		},
	}, nil
}

// GetFullURL returns the complete URL by combining OAuth2 base URL (with context path) and path
// All endpoints should go through the /closeauth context path
func (e *EndpointsConfig) GetFullURL(path string) string {
	return e.OAuth2.BaseURL + path
}

// GetOAuth2URL returns the complete OAuth2 URL
func (e *EndpointsConfig) GetOAuth2URL(path string) string {
	return e.OAuth2.BaseURL + path
}

// Helper methods for commonly used full URLs

// GetTokenURL returns the full token endpoint URL
func (e *EndpointsConfig) GetTokenURL() string {
	return e.GetOAuth2URL(e.OAuth2.Token)
}

// GetAuthorizeURL returns the full authorize endpoint URL
func (e *EndpointsConfig) GetAuthorizeURL() string {
	return e.GetOAuth2URL(e.OAuth2.Authorize)
}

// GetIntrospectURL returns the full introspect endpoint URL
func (e *EndpointsConfig) GetIntrospectURL() string {
	return e.GetOAuth2URL(e.OAuth2.Introspect)
}

// GetRevocationURL returns the full revocation endpoint URL
func (e *EndpointsConfig) GetRevocationURL() string {
	return e.GetOAuth2URL(e.OAuth2.Revocation)
}

// GetJWKSURL returns the full JWKS endpoint URL
func (e *EndpointsConfig) GetJWKSURL() string {
	return e.GetOAuth2URL(e.OAuth2.JWKS)
}

// GetRegisterClientURL returns the full client registration endpoint URL
func (e *EndpointsConfig) GetRegisterClientURL() string {
	return e.GetOAuth2URL(e.OAuth2.RegisterClient)
}

// GetAdminLoginURL returns the full admin login endpoint URL
func (e *EndpointsConfig) GetAdminLoginURL() string {
	return e.GetFullURL(e.CloseAuth.AdminLogin)
}

// GetAdminRegisterURL returns the full admin register endpoint URL
func (e *EndpointsConfig) GetAdminRegisterURL() string {
	return e.GetFullURL(e.CloseAuth.AdminRegister)
}

// GetAdminVerifyEmailURL returns the full admin verify email endpoint URL
func (e *EndpointsConfig) GetAdminVerifyEmailURL() string {
	return e.GetFullURL(e.CloseAuth.AdminVerifyEmail)
}

// GetAdminResendOTPURL returns the full admin resend OTP endpoint URL
func (e *EndpointsConfig) GetAdminResendOTPURL() string {
	return e.GetFullURL(e.CloseAuth.AdminResendOTP)
}

// GetAdminForgotPasswordURL returns the full admin forgot password endpoint URL
func (e *EndpointsConfig) GetAdminForgotPasswordURL() string {
	return e.GetFullURL(e.CloseAuth.AdminForgotPassword)
}

// GetAdminPasswordResetRequestURL returns the full admin password reset request endpoint URL
func (e *EndpointsConfig) GetAdminPasswordResetRequestURL() string {
	return e.GetFullURL(e.CloseAuth.AdminPasswordResetRequest)
}

// GetEnvOrDefault retrieves environment variable or returns default (exported for use in other packages)
func GetEnvOrDefault(key, defaultValue string) string {
	return getEnvOrDefault(key, defaultValue)
}

// getEnvOrDefault retrieves environment variable or returns default
func getEnvOrDefault(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
