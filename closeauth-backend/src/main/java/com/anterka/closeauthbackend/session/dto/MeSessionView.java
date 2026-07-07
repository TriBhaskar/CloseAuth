package com.anterka.closeauthbackend.session.dto;

import java.time.Instant;

/**
 * A self-service view of one of the caller's sessions (§7.8, {@code GET /v1/me/sessions}). Deliberately OMITS the
 * {@code session_key} (that is the session's bearer secret — never leaked); the {@code id} is the handle used to revoke.
 */
public record MeSessionView(
        java.util.UUID id,
        boolean rememberMe,
        String ipAddress,
        String userAgent,
        String amr,
        Instant createdAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant lastAccessedAt) {

    public static MeSessionView from(SessionView s) {
        return new MeSessionView(s.id(), s.rememberMe(), s.ipAddress(), s.userAgent(), s.amr(),
                s.createdAt(), s.idleExpiresAt(), s.absoluteExpiresAt(), s.lastAccessedAt());
    }
}
