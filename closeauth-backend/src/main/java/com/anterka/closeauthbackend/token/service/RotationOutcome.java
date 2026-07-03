package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.token.entity.RefreshToken;

/**
 * The decision produced by {@link RefreshTokenRotationService#authorizeRotation}. The SAS integration maps this to
 * "issue new tokens" (PROCEED) or "reject the refresh request" (everything else).
 *
 * @param type   the decision
 * @param parent for {@link Type#PROCEED}, the (now-USED) token being rotated; {@code null} otherwise
 */
public record RotationOutcome(Type type, RefreshToken parent) {

    public enum Type {
        /** The presented token was ACTIVE and this request won the atomic transition — issue the next token. */
        PROCEED,
        /** The presented token was already USED/REVOKED — a replay. The whole family has been revoked. Reject. */
        REPLAY,
        /** The presented token was ACTIVE but a concurrent request transitioned it first — benign race. Reject, no revoke. */
        RACE_LOST,
        /** The presented token is past its expiry — reject. Not a compromise, so no family revocation. */
        EXPIRED,
        /** No such token in the ledger — reject. */
        UNKNOWN
    }

    public static RotationOutcome proceed(RefreshToken parent) {
        return new RotationOutcome(Type.PROCEED, parent);
    }

    public static RotationOutcome replay() {
        return new RotationOutcome(Type.REPLAY, null);
    }

    public static RotationOutcome raceLost() {
        return new RotationOutcome(Type.RACE_LOST, null);
    }

    public static RotationOutcome expired() {
        return new RotationOutcome(Type.EXPIRED, null);
    }

    public static RotationOutcome unknown() {
        return new RotationOutcome(Type.UNKNOWN, null);
    }
}
