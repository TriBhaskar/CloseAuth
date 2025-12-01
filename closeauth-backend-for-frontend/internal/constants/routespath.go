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
)

// Authentication page routes (GET)
const (
	RouteAdminLogin          = "/admin/auth/login"
	RouteAdminRegister       = "/admin/auth/register"
	RouteAdminForgotPassword = "/admin/auth/forgot-password"
)

// Authentication action routes (POST)
const (
	RouteAuthLogin       = "/auth/login"
	RouteLogin           = "/login"
	RouteRegister        = "/register"
	RouteRegisterVerify  = "/register/verify-otp"
	RouteRegisterResend  = "/register/resend-otp"
	RouteForgotPasswordRequest = "/forgot-password/request"
	RouteForgotPasswordVerify  = "/forgot-password/verify-otp"
	RouteForgotPasswordResend  = "/forgot-password/resend-otp"
	RouteForgotPasswordReset   = "/forgot-password/reset"
)
