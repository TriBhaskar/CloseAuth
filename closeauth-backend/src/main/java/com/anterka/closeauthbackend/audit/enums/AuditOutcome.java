package com.anterka.closeauthbackend.audit.enums;

/**
 * Outcome of an audited action. Values must match the CHECK constraint on
 * {@code audit_events.outcome} exactly.
 */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    ERROR
}
