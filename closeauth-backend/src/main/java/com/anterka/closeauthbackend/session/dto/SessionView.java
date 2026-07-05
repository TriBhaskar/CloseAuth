package com.anterka.closeauthbackend.session.dto;

import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import com.anterka.closeauthbackend.session.service.SessionHotState;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of an Auth Server session (Convention 4 — output DTO). Services return this, never the
 * {@link AuthServerSession} JPA entity or the raw hot state.
 *
 * <p>Note on {@code sessionKey}: this internal service view carries it because {@code createSession}/{@code
 * validateSession} callers legitimately hold the key (it is the cookie value). The Stage 7 {@code /v1/me/sessions}
 * HTTP DTO must project a <b>safe subset</b> — it must NOT expose other devices' session keys in a listing.
 */
public record SessionView(
        UUID id,
        String sessionKey,
        UUID userId,
        UUID tenantId,
        boolean rememberMe,
        String ipAddress,
        String userAgent,
        String amr,
        Instant createdAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant lastAccessedAt,
        Instant revokedAt) {

    /**
     * Maps a managed {@link AuthServerSession} ledger entity to an immutable view. Call within a transaction.
     * {@code amr} is null here — it is a per-login method carried only in the Redis hot state (no ledger column).
     */
    public static SessionView from(AuthServerSession s) {
        return new SessionView(
                s.getId(),
                s.getSessionKey(),
                s.getUserId(),
                s.getTenantId(),
                s.isRememberMe(),
                s.getIpAddress(),
                s.getUserAgent(),
                null,
                s.getCreatedAt(),
                s.getIdleExpiresAt(),
                s.getAbsoluteExpiresAt(),
                s.getLastAccessedAt(),
                s.getRevokedAt());
    }

    /**
     * Builds a view from the Redis hot state (the {@code validateSession} hot path avoids a DB read for the decision).
     * {@code revokedAt} is null by construction — a hot-store hit that passed validation is, by definition, live.
     * {@code ipAddress}/{@code userAgent} are null (not carried in the hot state; read them from the ledger via
     * {@code getById} when needed).
     */
    public static SessionView fromHotState(SessionHotState st, Instant lastAccessedAt) {
        return new SessionView(
                st.ledgerId(),
                st.sessionKey(),
                st.userUuid(),
                st.tenantUuid(),
                st.rememberMe(),
                null,
                null,
                st.amr(),
                st.createdAtInstant(),
                st.idleExpiresAtInstant(),
                st.absoluteExpiresAtInstant(),
                lastAccessedAt,
                null);
    }
}
