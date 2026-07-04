package com.anterka.closeauthbackend.session.service;

import java.time.Instant;
import java.util.UUID;

/**
 * The hot-path session state stored in Redis (§7.5) — the authoritative source for the {@code validateSession}
 * decision (tenant, timeouts). Deliberately modelled with <b>only primitives/Strings/longs</b> (no {@link Instant},
 * no collections): this is the Stage 4b-ii D1 lesson applied — the value is JSON-serialized into Redis and must
 * round-trip cleanly with a plain {@code ObjectMapper}, so we store epoch-millis {@code long}s rather than temporal
 * types that need extra Jackson modules.
 *
 * @param id                        the durable ledger row id (so the hot path can act — e.g. session-scoped refresh
 *                                  revocation — without a DB read)
 * @param sessionKey                the opaque cookie value that identifies the session
 * @param userId                    the authenticated user (UUID as String)
 * @param tenantId                  the tenant the session is bound to (UUID as String) — the tenant-scoping anchor
 * @param rememberMe                whether remember-me extended the absolute cap
 * @param createdAtEpochMs          creation time
 * @param idleExpiresAtEpochMs      current sliding idle expiry (advances on each validation)
 * @param absoluteExpiresAtEpochMs  hard cap; never advances
 */
public record SessionHotState(
        String id,
        String sessionKey,
        String userId,
        String tenantId,
        boolean rememberMe,
        long createdAtEpochMs,
        long idleExpiresAtEpochMs,
        long absoluteExpiresAtEpochMs) {

    /** Returns a copy with the idle expiry advanced (used when sliding the window on a successful validation). */
    public SessionHotState withIdleExpiresAt(long newIdleExpiresAtEpochMs) {
        return new SessionHotState(id, sessionKey, userId, tenantId, rememberMe,
                createdAtEpochMs, newIdleExpiresAtEpochMs, absoluteExpiresAtEpochMs);
    }

    public Instant createdAtInstant() {
        return Instant.ofEpochMilli(createdAtEpochMs);
    }

    public Instant idleExpiresAtInstant() {
        return Instant.ofEpochMilli(idleExpiresAtEpochMs);
    }

    public Instant absoluteExpiresAtInstant() {
        return Instant.ofEpochMilli(absoluteExpiresAtEpochMs);
    }

    public UUID ledgerId() {
        return UUID.fromString(id);
    }

    public UUID userUuid() {
        return UUID.fromString(userId);
    }

    public UUID tenantUuid() {
        return UUID.fromString(tenantId);
    }
}
