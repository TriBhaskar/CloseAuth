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
	RouteAdminDashboard = "/admin/dashboard"
	RouteAdminUsers     = "/admin/users"
	RouteAdminClients   = "/admin/clients"
	RouteAdminClientNew = "/admin/clients/new"
	RouteAdminAnalytics = "/admin/analytics"
	RouteAdminSecurity  = "/admin/security"
	RouteAdminSettings  = "/admin/settings"
)

// Authentication page routes (GET)
const (
	RouteAdminLogin          = "/admin/auth/login"
	RouteAdminRegister       = "/admin/auth/register"
	RouteAdminForgotPassword = "/admin/auth/forgot-password"
)

// Authentication action routes (POST)
const (
	RouteLogin       = "/admin/auth/login"
	RouteRegister        = "/admin/auth/register"
	RouteRegisterVerify  = "/admin/auth/register/verify-otp"
	RouteRegisterResend  = "/admin/auth/register/resend-otp"
	RouteForgotPasswordRequest = "/admin/auth/forgot-password/request"
	RouteForgotPasswordVerify  = "/admin/auth/forgot-password/verify-otp"
	RouteForgotPasswordResend  = "/admin/auth/forgot-password/resend-otp"
	RouteForgotPasswordReset   = "/admin/auth/forgot-password/reset"
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
