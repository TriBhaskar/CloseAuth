package com.anterka.closeauthbackend.user.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Immutable carrier for the authenticated admin user's identity plus request
 * metadata needed for auditing. Built in the web layer and passed into services
 * so the service layer never depends on {@link HttpServletRequest}.
 *
 * @param userId    the authenticated admin user id (from the X-User-Token)
 * @param ipAddress best-effort client IP (resolved via trusted-proxy rules)
 * @param userAgent the originating User-Agent header (may be {@code null})
 */
public record UserActionContext(Integer userId, String ipAddress, String userAgent) {
}

