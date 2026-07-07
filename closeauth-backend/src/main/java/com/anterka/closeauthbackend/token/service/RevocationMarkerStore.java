package com.anterka.closeauthbackend.token.service;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Storage for access-token revocation markers (§7.3, Strategy 3). A marker means "all access tokens for this
 * subject issued at or before {@code timestamp} are revoked" — a single write kills all of a subject's outstanding
 * (stateless) tokens without enumerating them. Abstracted behind this interface so the revocation-check logic is
 * unit-testable without Redis.
 *
 * <p>Timestamps are epoch <b>seconds</b> (matching JWT {@code iat}). Reads FAIL OPEN (a backing-store outage
 * returns "no marker" so introspection degrades to plain JWT validity — the pre-existing 5-minute window).
 */
public interface RevocationMarkerStore {

    /** Writes a user-scoped revocation marker with the given TTL. */
    void revokeUser(UUID tenantId, UUID userId, Instant at, Duration ttl);

    /** Writes a tenant-scoped revocation marker (revokes all the tenant's users' tokens) with the given TTL. */
    void revokeTenant(UUID tenantId, Instant at, Duration ttl);

    /**
     * Writes a platform-admin revocation marker keyed by the admin's {@code sub} alone (§7.8). Platform admins are
     * TENANT-LESS (their tokens carry no {@code tenant_id}), so the tenant-scoped keys above cannot address them — this
     * is the sub-only marker for the third token shape.
     */
    void revokePlatformAdmin(UUID platformAdminId, Instant at, Duration ttl);

    /** The user marker's revocation time (epoch seconds), or empty if none / on a store outage (fail-open). */
    OptionalLong userRevocationEpochSeconds(UUID tenantId, UUID userId);

    /** The tenant marker's revocation time (epoch seconds), or empty if none / on a store outage (fail-open). */
    OptionalLong tenantRevocationEpochSeconds(UUID tenantId);

    /** The platform-admin marker's revocation time (epoch seconds), or empty if none / on a store outage (fail-open). */
    OptionalLong platformAdminRevocationEpochSeconds(UUID platformAdminId);
}
