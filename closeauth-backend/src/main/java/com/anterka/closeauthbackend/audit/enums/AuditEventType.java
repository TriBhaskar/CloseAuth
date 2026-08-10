package com.anterka.closeauthbackend.audit.enums;

/**
 * Canonical audit event taxonomy (Section 7.11).
 *
 * <p>The {@code audit_events.event_type} column deliberately has NO database CHECK
 * constraint so the taxonomy can evolve without a migration. This enum is therefore
 * the application-layer enforcement point: it is the single source of truth for the
 * set of valid event types. Adding a new event type is a code change (new enum
 * constant), never a schema migration.
 *
 * <p><b>Stage 8 additions (additive, per the no-CHECK-constraint decision):</b> the
 * §7.11 vision taxonomy (the values through {@code PLATFORM_CONFIGURATION_CHANGED})
 * was the seed. Wiring the actual seams surfaced finer-grained security-relevant events
 * the vision folded or omitted — session lifecycle, the one-time-token flows (email
 * verification / magic-link / password-reset / invite), and the explicit
 * {@code USER_SUSPENDED}/{@code USER_ACTIVATED}/{@code TENANT_ACTIVATED} lifecycle steps.
 * These are added here rather than smuggled into {@code event_data}, so each stays a
 * first-class, queryable event type.
 *
 * <p><b>Not-yet-emitted values</b> (no call site; deliberately un-wired, tracked by
 * {@code AuditTaxonomyCoverageTest}'s documented exclusion set): the {@code AGENT_*}
 * family (Phase 4 — {@code agents} is schema-only), {@code MFA_*} (Phase 2 — MFA is not
 * built), and {@code CLIENT_UPDATED}/{@code CLIENT_DELETED} and {@code USER_UPDATED}
 * (their admin endpoints — client update/delete, user profile PATCH — are the two small
 * 7b follow-ups, not yet built).
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
    REFRESH_TOKEN_REJECTED_TENANT_INACTIVE,

    // --- One-time-token auth flows (Stage 6b-i; wired Stage 8) ---
    EMAIL_VERIFICATION_ISSUED,
    EMAIL_VERIFIED,
    MAGIC_LINK_ISSUED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    ONE_TIME_TOKEN_CONSUME_FAILED,
    INVITE_ISSUED,
    INVITE_REVOKED,

    // --- Session (Stage 5; wired Stage 8) ---
    SESSION_CREATED,
    SESSION_REVOKED,

    // --- Identity ---
    USER_CREATED,
    USER_UPDATED,
    USER_SUSPENDED,
    USER_ACTIVATED,
    USER_DELETED,
    PASSWORD_CHANGED,
    MFA_ENROLLED,
    MFA_REMOVED,

    // --- Tenant ---
    TENANT_CREATED,
    TENANT_ACTIVATED,
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
    CLIENT_SECRET_REGENERATED,

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
