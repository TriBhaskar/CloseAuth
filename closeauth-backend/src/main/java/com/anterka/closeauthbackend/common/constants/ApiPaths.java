package com.anterka.closeauthbackend.common.constants;

public class ApiPaths {
    public static final String API_CONTEXT_PATH = "/closeauth";
    public static final String API_PREFIX = "/api";
    public static final String VERSION_V1 = "/v1";

    // Complete API base path
    public static final String API_V1_BASE = API_PREFIX + VERSION_V1;

    public static final String ADMIN_BASE = API_V1_BASE + "/admin";
    // Auth API base path
    public static final String AUTH_BASE = "/auth";
    // Authentication (separate from users for clarity)
    public static final String LOGIN = AUTH_BASE + "/login";
    public static final String LOGOUT = AUTH_BASE + "/logout";
    public static final String REGISTER = AUTH_BASE + "/register";
    public static final String VERIFY_EMAIL = AUTH_BASE + "/verify-email";
    public static final String RESEND_OTP = AUTH_BASE + "/resend-otp";
    public static final String FORGOT_PASSWORD = AUTH_BASE + "/forgot-password";
    public static final String RESET_PASSWORD = AUTH_BASE + "/reset-password";

    // OAUTH2 PATHS
    public static final String OAUTH2_BASE = "/oauth2";
    public static final String CLIENT_REGISTER_URL = "/connect/register";
    public static final String TOKEN_URL = OAUTH2_BASE + "/token";
    public static final String AUTHORIZE_URL = OAUTH2_BASE + "/authorize";
    public static final String LOGOUT_URL = OAUTH2_BASE + "/logout";
    public static final String JWKS_URL = OAUTH2_BASE + "/jwks";
    public static final String REVOCATION_URL = OAUTH2_BASE + "/revoke";
    public static final String INTROSPECTION_URL = OAUTH2_BASE + "/introspect";

    // Client Configuration Management
    public static final String CLIENT_CONFIG_BASE = API_V1_BASE + "/clients";

    // ========================================
    // SECURITY CONFIGURATION ARRAYS
    // ========================================

    /**
     * Admin authentication endpoints - require OAuth2 Bearer token with SCOPE_client.create.
     * These endpoints do NOT require X-User-Token as they establish user identity.
     */
    public static final String[] ADMIN_AUTH_ENDPOINTS = {
            ADMIN_BASE + REGISTER,
            ADMIN_BASE + LOGIN,
            ADMIN_BASE + VERIFY_EMAIL,
            ADMIN_BASE + RESEND_OTP,
            ADMIN_BASE + FORGOT_PASSWORD,
            ADMIN_BASE + RESET_PASSWORD
    };


    /**
     * Deprecated for now
     */
    // Legacy compatibility - deprecated, use ADMIN_AUTH_ENDPOINTS instead
    @Deprecated
    public static final String[] SKIP_AUTH_PATHS = ADMIN_AUTH_ENDPOINTS;

    private ApiPaths() {
        // Utility class - prevent instantiation
    }
}
