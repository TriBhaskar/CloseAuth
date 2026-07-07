package com.anterka.closeauthbackend.common.web;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The <b>canonical</b> RFC 7807 error model for the platform (§7.8, Stage 7a) — supersedes the 6b-i stopgap
 * {@code AuthDomainExceptionAdvice} (now removed), so there is ONE error mapping for every endpoint.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@link CloseAuthDomainException} → status from {@link ErrorCategory} (NOT_FOUND→404, CONFLICT→409,
 *       VALIDATION→400, FORBIDDEN→403, <b>STATE→409</b> — a state-transition violation conflicts with the current
 *       state); VALIDATION carries the per-property violations from 3a's {@code CommandValidator} context.</li>
 *   <li>{@link MethodArgumentNotValidException} (bean validation at the controller edge) → 400 with field errors.</li>
 *   <li>{@link AccessDeniedException} → 403, {@link AuthenticationException} → 401 (method-level; the resource-server
 *       chain also renders filter-level 401/403 as problem+json).</li>
 *   <li>catch-all {@link Exception} → 500 with a GENERIC detail — no stack trace, SQL, or internals in the body (the
 *       full error is logged server-side only).</li>
 * </ul>
 *
 * <p><b>Enumeration-safety:</b> admin lookups never differentiate "not found" via error <em>shape</em> in a way that
 * leaks existence — a genuinely-missing resource is a uniform 404; the enumeration-sensitive auth flows (6b-i) already
 * return uniform results rather than throwing, so this advice only renders safe-to-reveal conditions.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(CloseAuthDomainException.class)
    public ProblemDetail handleDomain(CloseAuthDomainException ex, HttpServletRequest request) {
        HttpStatus status = statusFor(ex.getCategory());
        ProblemDetail problem = ProblemDetails.of(status, ex.getCode(), ex.getMessage(), request.getRequestURI());
        if (ex.getCategory() == ErrorCategory.VALIDATION && !ex.getContext().isEmpty()) {
            problem.setProperty("errors", ex.getContext()); // per-property violations
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "validation.failed",
                "Request validation failed", request.getRequestURI());
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        // Method-security denial (@PreAuthorize). Generic — never reveals what would have been authorized.
        return ProblemDetails.of(HttpStatus.FORBIDDEN, "access_denied",
                "You do not have permission to perform this action.", request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                "Authentication is required.", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full detail is logged SERVER-SIDE ONLY; the response body is generic (no stack trace, SQL, or internals).
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.", request.getRequestURI());
    }

    private HttpStatus statusFor(ErrorCategory category) {
        return switch (category) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, STATE -> HttpStatus.CONFLICT; // STATE = state-transition conflict (chosen over 422)
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
    }
}
