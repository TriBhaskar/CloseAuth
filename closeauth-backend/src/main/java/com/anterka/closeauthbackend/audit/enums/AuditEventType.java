package com.anterka.closeauthbackend.audit.enums;

/**
 * Canonical audit event taxonomy (Section 7.11).
 *
 * <p>The {@code audit_events.event_type} column deliberately has NO database CHECK
 * constraint so the taxonomy can evolve without a migration. This enum is therefore
 * the application-layer enforcement point: it is the single source of truth for the
 * set of valid event types. Adding a new event type is a code change (new enum
 * constant), never a schema migration.
 */
public enum AuditEventType {

    // --- Authentication ---
    USER_LOGIN_SUCCESS,
    USER_LOGIN_FAILURE,
    USER_LOGOUT,
    TOKEN_ISSUED,
    TOKEN_REVOKED,
    TOKEN_INTROSPECTED,
    REFRESH_TOKEN_ROTATED,
    REFRESH_TOKEN_REPLAY_DETECTED,

    // --- Identity ---
    USER_CREATED,
    USER_UPDATED,
    USER_DELETED,
    PASSWORD_CHANGED,
    MFA_ENROLLED,
    MFA_REMOVED,

    // --- Tenant ---
    TENANT_CREATED,
    TENANT_SUSPENDED,
    TENANT_DELETED,
    TENANT_BRANDING_CHANGED,
    TENANT_REGISTRATION_POLICY_CHANGED,

    // --- Authorization ---
    ROLE_ASSIGNED,
    ROLE_REVOKED,
    CONSENT_GRANTED,
    CONSENT_REVOKED,

    // --- Client ---
    CLIENT_REGISTERED,
    CLIENT_UPDATED,
    CLIENT_DELETED,

    // --- Resource Server ---
    RESOURCE_SERVER_CREATED,
    SCOPE_DEFINED,
    SCOPE_REMOVED,

    // --- Agent ---
    AGENT_REGISTERED,
    AGENT_REVOKED,
    AGENT_CONSENT_GRANTED,
    AGENT_TOKEN_EXCHANGED,

    // --- Administrative ---
    ADMIN_LOGIN,
    PLATFORM_CONFIGURATION_CHANGED
}
