package constants

// OAuth2 proxy routes (browser navigation)
const (
	RouteOAuthAuthorize = "/closeauth/oauth2/authorize"
	RouteOAuthToken     = "/closeauth/oauth2/token"
	RouteOAuthConsent   = "/closeauth/oauth2/consent"
)

// Public API routes
const (
	RouteAPICSRF   = "/api/csrf"
	RouteAPIHealth = "/api/health"
)

// Admin auth API routes (public — no session required)
const (
	RouteAPIAdminLogin                  = "/api/admin/login"
	RouteAPIAdminRegister               = "/api/admin/register"
	RouteAPIAdminRegisterVerifyOTP      = "/api/admin/register/verify-otp"
	RouteAPIAdminRegisterResendOTP      = "/api/admin/register/resend-otp"
	RouteAPIForgotPasswordRequest       = "/api/admin/forgot-password/request"
	RouteAPIForgotPasswordValidateToken = "/api/admin/forgot-password/validate-token"
	RouteAPIForgotPasswordReset         = "/api/admin/forgot-password/reset"
)

// OAuth client API routes (public — theme, login, register, consent-data)
const (
	RouteAPIOAuthTheme          = "/api/oauth/theme"
	RouteAPIOAuthLogin          = "/api/oauth/login"
	RouteAPIOAuthRegister       = "/api/oauth/register"
	RouteAPIOAuthRegisterVerify = "/api/oauth/register/verify-otp"
	RouteAPIOAuthRegisterResend = "/api/oauth/register/resend-otp"
	RouteAPIOAuthConsentData    = "/api/oauth/consent-data"
)

// Protected admin API routes (session required)
const (
	RouteAPIAdminMe        = "/api/admin/me"
	RouteAPIAdminLogout    = "/api/admin/logout"
	RouteAPIAdminDashboard = "/api/admin/dashboard"
	RouteAPIAdminUsers     = "/api/admin/users"
	RouteAPIAdminClients   = "/api/admin/clients"
	RouteAPIAdminAnalytics = "/api/admin/analytics"
	RouteAPIAdminSecurity  = "/api/admin/security"
	RouteAPIAdminSettings  = "/api/admin/settings"
)
