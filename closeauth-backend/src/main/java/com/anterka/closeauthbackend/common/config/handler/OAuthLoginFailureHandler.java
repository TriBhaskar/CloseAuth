package com.anterka.closeauthbackend.common.config.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Custom failure handler for the OAuth2 form-login flow.
 *
 * Instead of Spring's default behaviour (302 redirect to "/login?error"),
 * this handler returns HTTP 401 with a JSON error body.
 *
 * This allows the BFF to reliably distinguish authentication failure
 * from success — it checks the status code (401 vs 200), not the
 * redirect target.
 */
@Slf4j
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {

        log.warn("OAuth form login failed: {}", exception.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String errorCode = resolveErrorCode(exception);
        String message = resolveMessage(exception);

        Map<String, Object> body = Map.of(
                "error", errorCode,
                "message", message
        );

        objectMapper.writeValue(response.getWriter(), body);
    }

    private String resolveErrorCode(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "invalid_credentials";
        }
        if (exception instanceof LockedException) {
            return "account_locked";
        }
        return "authentication_failed";
    }

    private String resolveMessage(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "Invalid username or password";
        }
        if (exception instanceof LockedException) {
            return "Account is locked. Please try again later.";
        }
        return "Authentication failed. Please try again.";
    }
}

