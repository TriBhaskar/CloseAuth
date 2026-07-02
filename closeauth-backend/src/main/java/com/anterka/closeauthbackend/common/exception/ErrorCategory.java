package com.anterka.closeauthbackend.common.exception;

/**
 * Coarse, HTTP-agnostic classification of a {@link CloseAuthDomainException}.
 *
 * <p>The service layer only ever speaks in these categories; it never references HTTP
 * status codes. Stage 7 owns the single mapping from category to an HTTP status and
 * RFC 7807 {@code type} URI, so the domain stays transport-independent.
 *
 * <p>Indicative Stage 7 mapping (not binding here):
 * <ul>
 *   <li>{@link #NOT_FOUND} → 404</li>
 *   <li>{@link #CONFLICT} → 409</li>
 *   <li>{@link #VALIDATION} → 400</li>
 *   <li>{@link #FORBIDDEN} → 403</li>
 *   <li>{@link #STATE} → 409 (illegal state transition)</li>
 * </ul>
 */
public enum ErrorCategory {
    NOT_FOUND,
    CONFLICT,
    VALIDATION,
    FORBIDDEN,
    STATE
}
