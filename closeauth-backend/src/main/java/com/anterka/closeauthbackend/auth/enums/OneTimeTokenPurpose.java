package com.anterka.closeauthbackend.auth.enums;

/**
 * The purpose a one-time token is minted for (§13.4). A token is <b>purpose-bound</b>: it is verified on consume, so a
 * token minted for one purpose (e.g. {@code PASSWORD_RESET}) is rejected if presented for another (e.g.
 * {@code MAGIC_LINK}). Values must match the {@code one_time_tokens.purpose} CHECK constraint exactly.
 */
public enum OneTimeTokenPurpose {
    /** Short numeric code (or link) proving control of the registration email. */
    EMAIL_VERIFICATION,
    /** Opaque high-entropy link that authenticates the user in lieu of a password. */
    MAGIC_LINK,
    /** Opaque high-entropy link authorizing a password reset. */
    PASSWORD_RESET,
    /** Opaque high-entropy link authorizing registration into a tenant (invite-only mode). */
    INVITE,
    /** Opaque high-entropy link letting a newly-provisioned tenant admin rotate their system-generated temp credential. */
    TENANT_ADMIN_ONBOARDING
}
