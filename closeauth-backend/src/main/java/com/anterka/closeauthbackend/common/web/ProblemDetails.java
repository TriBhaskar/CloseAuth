package com.anterka.closeauthbackend.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * Builds RFC 7807 Problem Details (§7.8) consistently across the platform — used by both {@link ApiExceptionHandler}
 * (domain/validation/unexpected errors) and the resource-server 401/403 handlers. The response media type is
 * {@code application/problem+json} (Spring renders {@link ProblemDetail} as such).
 *
 * <p><b>Leak-free:</b> callers pass only safe {@code detail} text; never a stack trace, SQL, or internal identifiers.
 */
public final class ProblemDetails {

    /** {@code type} URI base — a stable, machine-navigable per-code documentation link. */
    public static final String TYPE_BASE = "https://docs.closeauth.io/errors/";

    private ProblemDetails() {
    }

    /**
     * @param status   HTTP status (also the {@code status} member)
     * @param code     machine-readable CloseAuth code (the {@code code} extension + the {@code type} URI suffix)
     * @param detail   human-readable, safe-to-expose detail
     * @param instance the request path
     */
    public static ProblemDetail of(HttpStatus status, String code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(TYPE_BASE + code));
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code); // CloseAuth extension: the stable machine code
        return problem;
    }
}
