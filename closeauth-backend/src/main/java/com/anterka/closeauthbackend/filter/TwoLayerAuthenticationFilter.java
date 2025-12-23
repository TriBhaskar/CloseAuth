package com.anterka.closeauthbackend.filter;

import com.anterka.closeauthbackend.security.UserContextHelper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * X-User-Token validation filter (Dual Authentication - User Layer).
 *
 * This filter validates the X-User-Token JWT header and extracts admin user information,
 * storing it in request attributes WITHOUT modifying SecurityContext.
 *
 * Dual Authentication Model:
 * - OAuth2 Bearer Token (in SecurityContext) = BFF client identity with SCOPE_client.create
 * - X-User-Token (validated here, stored in request attributes) = Admin user identity
 *
 * This approach prevents authentication conflicts and maintains access to both
 * client (OAuth2) and user (X-User-Token) information throughout the request.
 *
 * NOTE: This filter is manually registered only for specific endpoints requiring
 * dual authentication (/api/v1/clients/**, /connect/register), not globally.
 */
@RequiredArgsConstructor
@Slf4j
public class TwoLayerAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_TOKEN_HEADER = "X-User-Token";

    private final JwtDecoder jwtDecoder;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        log.info("Validating X-User-Token for request: {} {}", request.getMethod(), request.getRequestURI());

        // Extract token from X-User-Token header
        String token = request.getHeader(USER_TOKEN_HEADER);

        if (token == null || token.isEmpty()) {
            log.warn("X-User-Token header is missing");
            sendUnauthorizedResponse(response, "X-User-Token header is required");
            return;
        }

        // Validate token and extract user information
        if (!validateAndStoreUserInfo(token, request)) {
            log.warn("X-User-Token validation failed - returning 401");
            sendUnauthorizedResponse(response, "Invalid or expired X-User-Token");
            return;
        }

        // Token is valid, user info stored in request attributes, continue
        log.info("X-User-Token validated successfully for user: {}", request.getAttribute(UserContextHelper.ATTR_USERNAME));
        filterChain.doFilter(request, response);
    }

    /**
     * Send JSON error response for authentication failures.
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                String.format("{\"error\":\"Unauthorized\",\"message\":\"%s\"}", message)
        );
    }

    /**
     * Validates X-User-Token JWT and stores user information in request attributes.
     * Does NOT modify SecurityContext - OAuth2 authentication remains intact.
     *
     * @param token the JWT token from X-User-Token header
     * @param request the HTTP request to store attributes in
     * @return true if validation successful, false otherwise
     */
    private boolean validateAndStoreUserInfo(String token, HttpServletRequest request) {
        try {
            Jwt jwt = jwtDecoder.decode(token);

            String username = jwt.getSubject();
            if (username == null) {
                log.warn("X-User-Token subject is null");
                return false;
            }

            // Extract userId from JWT claims
            Integer userId = jwt.getClaim("userId");
            if (userId == null) {
                log.warn("X-User-Token userId claim is missing");
                return false;
            }

            // Extract roles from JWT claims
            List<String> roles = extractRoles(jwt);

            // Store user information in request attributes (NOT SecurityContext)
            request.setAttribute(UserContextHelper.ATTR_USER_ID, userId);
            request.setAttribute(UserContextHelper.ATTR_USERNAME, username);
            request.setAttribute(UserContextHelper.ATTR_USER_ROLES, roles);

            log.info("User info stored in request: username={}, userId={}, roles={}", username, userId, roles);
            return true;

        } catch (JwtException e) {
            log.error("X-User-Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts roles from JWT claims as a list of strings.
     */
    private List<String> extractRoles(Jwt jwt) {
        Object rolesClaim = jwt.getClaim("roles");

        if (rolesClaim instanceof Collection<?> roles) {
            return roles.stream()
                    .map(Object::toString)
                    .toList();
        }

        log.info("No roles found in X-User-Token for user: {}", jwt.getSubject());
        return List.of();
    }
}
