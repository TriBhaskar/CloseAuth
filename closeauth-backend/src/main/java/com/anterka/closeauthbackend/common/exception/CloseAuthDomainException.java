package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * Base type for all CloseAuth domain (business-rule) exceptions. Unchecked.
 *
 * <p>Carries enough structured, HTTP-agnostic information for Stage 7 to render an
 * RFC 7807 Problem Details response without the service layer knowing anything about
 * HTTP:
 * <ul>
 *   <li>{@link #getCategory() category} — coarse class ({@link ErrorCategory}) that
 *       Stage 7 maps to an HTTP status;</li>
 *   <li>{@link #getCode() code} — a stable, machine-readable identifier
 *       (e.g. {@code tenant.slug_conflict}) that Stage 7 maps to a {@code type} URI;</li>
 *   <li>{@link #getContext() context} — an immutable map of structured fields
 *       (e.g. {@code {slug: "acme"}}) that can be surfaced as Problem Details extensions.</li>
 * </ul>
 *
 * <p>Deliberately NOT abstract so a generic domain error can be thrown directly when a
 * dedicated subclass would be overkill; prefer a specific subclass where one exists.
 */
public class CloseAuthDomainException extends RuntimeException {

    private final ErrorCategory category;
    private final String code;
    private final transient Map<String, Object> context;

    public CloseAuthDomainException(ErrorCategory category, String code, String message) {
        this(category, code, message, Map.of());
    }

    public CloseAuthDomainException(ErrorCategory category, String code, String message,
                                    Map<String, Object> context) {
        super(message);
        this.category = category;
        this.code = code;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public String getCode() {
        return code;
    }

    /** Immutable map of structured, HTTP-agnostic context fields. */
    public Map<String, Object> getContext() {
        return context;
    }
}
