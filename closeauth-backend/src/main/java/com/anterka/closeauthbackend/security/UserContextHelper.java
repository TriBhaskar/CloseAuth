package com.anterka.closeauthbackend.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Utility class for extracting authenticated user information from request attributes.
 *
 * The X-User-Token JWT is validated by TwoLayerAuthenticationFilter and user information
 * is stored in request attributes rather than SecurityContext to avoid conflicts with
 * OAuth2 client authentication.
 *
 * Usage:
 * - OAuth2 Bearer Token (in SecurityContext) = BFF client identity with SCOPE_client.create
 * - X-User-Token (in request attributes) = Admin user identity
 */
@Slf4j
public class UserContextHelper {

    // Request attribute keys
    public static final String ATTR_USER_ID = "authenticatedUserId";
    public static final String ATTR_USERNAME = "authenticatedUsername";
    public static final String ATTR_USER_ROLES = "userRoles";

    private UserContextHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Extract authenticated user ID from request attributes.
     *
     * @param request the HTTP servlet request
     * @return the user ID
     * @throws IllegalStateException if user is not authenticated
     */
    public static Integer getUserId(HttpServletRequest request) {
        Integer userId = (Integer) request.getAttribute(ATTR_USER_ID);
        if (userId == null) {
            log.error("Attempted to access user ID but user is not authenticated");
            throw new IllegalStateException("User not authenticated - X-User-Token validation failed or missing");
        }
        return userId;
    }

    /**
     * Extract authenticated username from request attributes.
     *
     * @param request the HTTP servlet request
     * @return the username
     * @throws IllegalStateException if user is not authenticated
     */
    public static String getUsername(HttpServletRequest request) {
        String username = (String) request.getAttribute(ATTR_USERNAME);
        if (username == null) {
            log.error("Attempted to access username but user is not authenticated");
            throw new IllegalStateException("User not authenticated - X-User-Token validation failed or missing");
        }
        return username;
    }

    /**
     * Extract authenticated user roles from request attributes.
     *
     * @param request the HTTP servlet request
     * @return list of role names, or empty list if no roles
     */
    @SuppressWarnings("unchecked")
    public static List<String> getUserRoles(HttpServletRequest request) {
        List<String> roles = (List<String>) request.getAttribute(ATTR_USER_ROLES);
        return roles != null ? roles : List.of();
    }

    /**
     * Check if user is authenticated (has valid X-User-Token).
     *
     * @param request the HTTP servlet request
     * @return true if user is authenticated
     */
    public static boolean isAuthenticated(HttpServletRequest request) {
        return request.getAttribute(ATTR_USER_ID) != null;
    }
}

