package com.anterka.closeauthbackend.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * RFC 7807 renderers for <b>filter-level</b> security failures on the admin API (§7.8, Stage 7a): a missing/invalid
 * bearer token → 401, and an authenticated-but-unauthorized filter denial → 403 — both as {@code application/problem+json},
 * matching {@link ApiExceptionHandler}'s method-level responses so the error model is uniform end to end.
 */
public final class ProblemDetailAuthEntryPoints {

    private ProblemDetailAuthEntryPoints() {
    }

    /** 401 for missing/invalid credentials (BearerTokenAuthenticationFilter → this entry point). */
    public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> write(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                "unauthorized", "Authentication is required.");
    }

    /** 403 for an authenticated caller a filter-level rule denies. */
    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> write(objectMapper, request, response, HttpStatus.FORBIDDEN,
                "access_denied", "You do not have permission to perform this action.");
    }

    private static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                              HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, code, detail, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
