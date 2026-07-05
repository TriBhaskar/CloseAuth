package com.anterka.closeauthbackend.tenant.enums;

/**
 * A tenant's self-registration mode (§9.2). Selects the registration strategy the auth flow applies. Values must match
 * the {@code tenant_registration_config.mode} CHECK constraint exactly.
 */
public enum RegistrationMode {
    /** Anyone can self-register; the user is usable immediately (subject to email verification if separately required). */
    OPEN,
    /** Self-register as PENDING; an email-verification token must be consumed to activate. */
    EMAIL_VERIFIED,
    /** Self-register as PENDING; a tenant admin must approve (approval trigger is Stage 7). */
    ADMIN_APPROVED,
    /** Registration requires consuming a valid INVITE token (invite issuance is Stage 7). */
    INVITE_ONLY
}
