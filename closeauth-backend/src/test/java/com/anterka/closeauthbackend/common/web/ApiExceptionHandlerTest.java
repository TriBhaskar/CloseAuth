package com.anterka.closeauthbackend.common.web;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical RFC 7807 mapping — every {@link ErrorCategory} maps to the intended HTTP status, VALIDATION carries its
 * per-property {@code errors}, and (critically) the catch-all never leaks internals: a raw exception message must not
 * appear in the 500 body.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = request("/v1/platform/admins");

    @Test
    void notFoundMapsTo404() {
        ProblemDetail pd = handler.handleDomain(
                new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_admin.not_found", "nope"), request);

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("code", "platform_admin.not_found");
        assertThat(pd.getType().toString()).endsWith("/platform_admin.not_found");
    }

    @Test
    void conflictMapsTo409() {
        ProblemDetail pd = handler.handleDomain(
                new CloseAuthDomainException(ErrorCategory.CONFLICT, "x", "dup"), request);
        assertThat(pd.getStatus()).isEqualTo(409);
    }

    @Test
    void stateTransitionMapsTo409() {
        ProblemDetail pd = handler.handleDomain(
                new CloseAuthDomainException(ErrorCategory.STATE, "x", "bad transition"), request);
        assertThat(pd.getStatus()).isEqualTo(409); // STATE chosen to render as 409 (documented)
    }

    @Test
    void forbiddenMapsTo403() {
        ProblemDetail pd = handler.handleDomain(
                new CloseAuthDomainException(ErrorCategory.FORBIDDEN, "x", "no"), request);
        assertThat(pd.getStatus()).isEqualTo(403);
    }

    @Test
    void validationMapsTo400AndCarriesPerFieldErrors() {
        CloseAuthDomainException ex = new CloseAuthDomainException(ErrorCategory.VALIDATION, "validation.failed",
                "invalid", Map.of("email", "must be a valid email"));

        ProblemDetail pd = handler.handleDomain(ex, request);

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsKey("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) pd.getProperties().get("errors");
        assertThat(errors).containsEntry("email", "must be a valid email");
    }

    @Test
    void accessDeniedMapsToGeneric403() {
        ProblemDetail pd = handler.handleAccessDenied(new AccessDeniedException("denied"), request);
        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getDetail()).doesNotContain("denied"); // never echoes what would have been authorized
    }

    @Test
    void unexpectedExceptionMapsToGeneric500WithoutLeakingInternals() {
        RuntimeException boom = new RuntimeException("NullPointer at com.internal.Secret line 42; SQL: select * from x");

        ProblemDetail pd = handler.handleUnexpected(boom, request("/v1/x"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(pd.getDetail()).doesNotContain("SQL", "NullPointer", "Secret"); // no internals in the body
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }
}
