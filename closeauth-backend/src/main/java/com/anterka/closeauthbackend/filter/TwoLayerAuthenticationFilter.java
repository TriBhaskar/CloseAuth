package com.anterka.closeauthbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateless JWT authentication filter.
 * Validates JWT tokens and extracts user information directly from claims.
 * No database calls - fully stateless!
 *
 * NOTE: This filter is manually registered in AuthorisationServerConfig,
 * not auto-discovered by Spring Boot.
 */
@RequiredArgsConstructor
@Slf4j
public class TwoLayerAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_TOKEN_HEADER = "X-User-Token";

    private final JwtDecoder jwtDecoder;

//    @Override
//    protected boolean shouldNotFilter(HttpServletRequest request){
//        String path = request.getServletPath();
//        log.info("Checking if path should skip auth filter: {}", path);
//        return Arrays.asList(ApiPaths.SKIP_AUTH_PATHS).contains(path);
//    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        log.info("Processing request for JWT authentication: {} {}", request.getMethod(), request.getRequestURI());

        // Extract token from X-User-Token header only
        String token = request.getHeader(USER_TOKEN_HEADER);

        if (token == null || token.isEmpty()) {
            log.warn("X-User-Token header is missing");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"X-User-Token header is required\"}");
            return; // Stop filter chain execution
        }

        // Validate and authenticate the token
        if (!authenticateFromToken(token)) {
            log.warn("JWT validation failed - returning 401");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired token\"}");
            return; // Stop filter chain execution
        }

        // Token is valid, continue the filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Validates JWT and creates authentication from token claims.
     * Completely stateless - all user info comes from the JWT.
     *
     * @return true if authentication successful, false otherwise
     */
    private boolean authenticateFromToken(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);

            String username = jwt.getSubject();
            if (username == null) {
                log.warn("JWT subject is null");
                return false;
            }

            // Extract roles from JWT claims
            Collection<SimpleGrantedAuthority> authorities = extractAuthorities(jwt);

            // Create authentication with info from JWT claims only
            var authToken = new UsernamePasswordAuthenticationToken(
                    username,  // Principal is the username
                    null,      // No credentials needed
                    authorities
            );

            SecurityContextHolder.getContext().setAuthentication(authToken);
            log.info("User authenticated from JWT: {} with roles: {}", username, authorities);
            return true;

        } catch (JwtException e) {
            log.error("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts authorities/roles from JWT claims.
     */
    private Collection<SimpleGrantedAuthority> extractAuthorities(Jwt jwt) {
        // Extract roles claim from JWT
        Object rolesClaim = jwt.getClaim("roles");

        if (rolesClaim instanceof Collection<?> roles) {
            return roles.stream()
                    .map(Object::toString)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }

        log.info("No roles found in JWT for user: {}", jwt.getSubject());
        return List.of(); // Empty list if no roles
    }
}
