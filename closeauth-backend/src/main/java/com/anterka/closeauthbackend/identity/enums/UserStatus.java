package com.anterka.closeauthbackend.identity.enums;

/**
 * User lifecycle states. Values must match the CHECK constraint on
 * {@code users.status} exactly.
 */
public enum UserStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    DELETED
}
