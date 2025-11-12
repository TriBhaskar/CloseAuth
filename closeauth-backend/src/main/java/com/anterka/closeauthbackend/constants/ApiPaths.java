package com.anterka.closeauthbackend.constants;

public class ApiPaths {
    public static final String API_CONTEXT_PATH = "/closeauth";
    public static final String API_PREFIX = "/api";
    public static final String VERSION_V1 = "/v1";

    // Complete API base path
    public static final String API_V1_BASE = API_PREFIX + VERSION_V1;

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


    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Get full API path with context, prefix and version
     * Example: /bjyotish/api/v1/users/profile
     */
    public static String getFullPath(String apiPath) {
        return API_CONTEXT_PATH + API_V1_BASE + apiPath;
    }

    /**
     * Get API path with version for use in @RequestMapping
     * Example: /api/v1/users/profile
     */
    public static String getVersionedPath(String path) {
        return API_V1_BASE + path;
    }

    /**
     * Get API path without version (for future v2, v3 etc.)
     * Example: /api/users/profile
     */
    public static String getApiPath(String path) {
        return API_PREFIX + path;
    }

    private ApiPaths() {}
}
