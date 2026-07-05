package com.anterka.closeauthbackend.auth.enums;

/**
 * The presentation/entropy of a one-time secret. Same security properties (hash-stored, single-use, expiry,
 * tenant/purpose-bound) either way — only the raw form differs.
 */
public enum OneTimeTokenFormat {
    /**
     * Short numeric code the user types (e.g. 6 digits). <b>Low entropy</b> (~1M for 6 digits) — MUST be paired with
     * a short expiry + strict issuance rate-limit + a per-target consume attempt-lockout (a code is brute-forceable
     * otherwise). Suitable for email/OTP verification.
     */
    NUMERIC_CODE,
    /**
     * Opaque high-entropy token (256-bit, base64url) embedded in a link the user clicks. High entropy → can have a
     * longer expiry. Suitable for magic-link, password-reset, and invite links.
     */
    OPAQUE_LINK
}
