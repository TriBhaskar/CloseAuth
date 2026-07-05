package com.anterka.closeauthbackend.auth.enums;

/**
 * How a user proved their identity for THIS login — recorded in the token's OIDC {@code amr} claim (RFC 8176,
 * "Authentication Methods References"). This is distinct from {@code idp} (§12): {@code idp} is <b>where the user's
 * identity/credential lives</b> (e.g. {@code LOCAL_PASSWORD}); {@code amr} is <b>the method used this time</b>. A magic
 * link does not introduce a new identity — the user is still their existing (LOCAL_PASSWORD) identity — it is just an
 * alternate way of proving email control in lieu of a password, so it is modeled as an {@code amr} method, NOT a new
 * {@code idp_type}. This is also the forward-compatible home for MFA method signals.
 */
public enum AuthMethod {
    /** Username/password. RFC 8176 {@code pwd}. */
    PASSWORD("pwd"),
    /** Magic-link (proof of email control via a one-time link). Implementation-specific amr value (RFC 8176 is extensible). */
    MAGIC_LINK("magic_link");

    private final String amrValue;

    AuthMethod(String amrValue) {
        this.amrValue = amrValue;
    }

    /** The RFC 8176 amr string for this method. */
    public String amrValue() {
        return amrValue;
    }
}
