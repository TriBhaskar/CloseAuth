package com.anterka.closeauthbackend.audit.web;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Lenient parsing of the audit query params into typed values, turning bad input into a clean RFC 7807 400 (via
 * {@link CloseAuthDomainException} → the existing {@code ApiExceptionHandler}) rather than a 500. Shared by the tenant
 * and platform audit controllers so both validate identically.
 */
final class AuditQueryParams {

    private AuditQueryParams() {
    }

    static AuditEventType eventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditEventType.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            throw invalid("event_type", raw);
        }
    }

    /** ISO-8601 instant (e.g. {@code 2026-01-01T00:00:00Z}). */
    static Instant instant(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException bad) {
            throw invalid(field, raw);
        }
    }

    static UUID uuid(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException bad) {
            throw invalid(field, raw);
        }
    }

    private static CloseAuthDomainException invalid(String field, String raw) {
        return new CloseAuthDomainException(ErrorCategory.VALIDATION, "audit.invalid_" + field,
                "Invalid value for '" + field + "': " + raw);
    }
}
