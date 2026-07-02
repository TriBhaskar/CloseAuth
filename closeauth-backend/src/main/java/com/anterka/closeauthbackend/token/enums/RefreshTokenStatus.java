package com.anterka.closeauthbackend.token.enums;

/**
 * Refresh token rotation states (Section 7.3). Values must match the CHECK
 * constraint on {@code refresh_tokens.status} exactly.
 */
public enum RefreshTokenStatus {
    ACTIVE,
    USED,
    REVOKED,
    EXPIRED
}
