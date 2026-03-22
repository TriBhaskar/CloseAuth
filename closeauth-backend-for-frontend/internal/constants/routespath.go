package constants

// Health and monitoring
const (
	RouteHealth = "/health"
)

// OAuth2 proxy routes
const (
	RouteOAuthAuthorize = "/closeauth/oauth2/authorize"
	RouteOAuthToken     = "/closeauth/oauth2/token"
)

// Static files
const (
	RouteStatic = "/static/"
)

// Public routes
const (
	RouteHome = "/"
)

// Admin routes
const (
	RouteAdminDashboard         = "/admin/dashboard"
	RouteAdminUsers             = "/admin/users"
	RouteAdminClients           = "/admin/clients"
	RouteAdminClientNew         = "/admin/clients/new"
	RouteAdminAnalytics         = "/admin/analytics"
	RouteAdminSecurity          = "/admin/security"
	RouteAdminSettings          = "/admin/settings"
	RouteAdminLogin             = "/admin/login"
	RouteAdminRegister          = "/admin/register"
	RouteAdminRegisterOTP       = "/admin/register/otp"
	RouteAdminRegisterVerifyOTP = "/admin/register/verify-otp"
	RouteAdminForgotPassword    = "/admin/forgot-password"
	RouteAdminLogout            = "/admin/logout"
)

// Authentication action routes (POST)
const (
	RouteLogin                 = "/admin/login"
	RouteRegister              = "/admin/register"
	RouteRegisterVerify        = "/admin/register/verify-otp"
	RouteRegisterResend        = "/admin/register/resend-otp"
	RouteForgotPasswordRequest = "/admin/forgot-password/request"
	RouteForgotPasswordVerify  = "/admin/forgot-password/verify-otp"
	RouteForgotPasswordResend  = "/admin/forgot-password/resend-otp"
	RouteForgotPasswordReset   = "/admin/forgot-password/reset"
)

// OAuth2 client authentication routes (client-specific themed pages)
const (
	// GET routes - display themed pages
	RouteOAuthClientLogin    = "/oauth/login"
	RouteOAuthClientRegister = "/oauth/register"
	RouteOAuthConsent        = "/oauth/consent"
	
	// POST routes - handle form submissions
	RouteOAuthClientLoginPost          = "/closeauth/login"
	RouteOAuthClientRegisterPost       = "/oauth/register"
	RouteOAuthClientRegisterVerifyOTP  = "/oauth/register/verify-otp"
	RouteOAuthClientRegisterResendOTP  = "/oauth/register/resend-otp"
)
