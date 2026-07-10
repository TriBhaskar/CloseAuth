package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Instant access-token revocation (§7.3, Strategy 3). Admin/security actions write revocation markers; the
 * introspection endpoint consults them so a revoked-but-not-yet-expired JWT reports {@code active: false}.
 *
 * <p>Access tokens are stateless 5-minute JWTs validated locally by resource servers — they cannot be enumerated or
 * individually deleted. Instead a marker records "everything for this subject issued at/before now is revoked", and
 * introspection compares a token's {@code iat} against it. Markers expire (TTL = max access-token TTL) once every
 * token they could suppress has expired anyway, keeping the list small.
 *
 * <p>Capabilities here are called by: 4b-i's replay path (wired), and — as documented Stage 7 integration points —
 * user suspension/deletion (3b {@code UserService} transitions) and tenant suspension (3a {@code TenantService}).
 * Stage 5's session revocation will be another caller. This service does not reach into those modules; Stage 7
 * orchestrates the admin actions that call these capabilities (same discipline as 4b-i's family-revocation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRevocationService {

    private final RevocationMarkerStore markerStore;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /** Revokes all outstanding access tokens for a user (e.g. user suspension/deletion, or a detected token theft). */
    public void revokeAllUserTokens(UUID tenantId, UUID userId) {
        markerStore.revokeUser(tenantId, userId, Instant.now(), properties.getToken().getAccessTokenTtl());
        log.info("Access-token revocation marker written for user {} in tenant {}", userId, tenantId);
        auditEmitter.emit(AuditEvents.userTokensRevoked(tenantId, userId));
    }

    /** Revokes all outstanding access tokens for every user in a tenant (e.g. tenant suspension). */
    public void revokeAllTenantTokens(UUID tenantId) {
        markerStore.revokeTenant(tenantId, Instant.now(), properties.getToken().getAccessTokenTtl());
        log.info("Access-token revocation marker written for ALL users in tenant {}", tenantId);
        auditEmitter.emit(AuditEvents.tenantTokensRevoked(tenantId));
    }

    /**
     * Revokes all outstanding access tokens for a PLATFORM ADMIN (§7.8) — e.g. suspension or a detected credential
     * theft. Platform-admin tokens are tenant-less (no {@code tenant_id}), so this uses the sub-only marker rather than
     * the tenant-scoped ones. The marker TTL is the platform-admin token TTL, so the marker outlives every token it
     * could suppress (then self-expires, keeping the list small).
     */
    public void revokePlatformAdminTokens(UUID platformAdminId) {
        markerStore.revokePlatformAdmin(platformAdminId, Instant.now(), properties.getPlatformAdmin().getTokenTtl());
        log.info("Access-token revocation marker written for platform admin {}", platformAdminId);
        auditEmitter.emit(AuditEvents.platformAdminTokensRevoked(platformAdminId));
    }

    /**
     * The revocation check consulted by introspection: is a token revoked given its subject and issued-at?
     *
     * <p>Boundary is {@code iat <= revocationTime} (SAFE direction): a token issued in the same second as the
     * revocation is treated as revoked. {@code iat} and the marker are both epoch seconds (no seconds/millis mismatch).
     *
     * @param tenantId the token's {@code tenant_id} claim (nullable)
     * @param userId   the token's {@code sub} as a user UUID, or {@code null} for a machine (client-credentials) token
     * @param iatEpochSeconds the token's {@code iat} in epoch seconds
     */
    public boolean isRevoked(UUID tenantId, UUID userId, long iatEpochSeconds) {
        if (tenantId != null) {
            OptionalLong tenantRevocation = markerStore.tenantRevocationEpochSeconds(tenantId);
            if (tenantRevocation.isPresent() && iatEpochSeconds <= tenantRevocation.getAsLong()) {
                return true;
            }
        }
        if (userId != null && tenantId != null) {
            OptionalLong userRevocation = markerStore.userRevocationEpochSeconds(tenantId, userId);
            if (userRevocation.isPresent() && iatEpochSeconds <= userRevocation.getAsLong()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The revocation check for a PLATFORM-ADMIN token (§7.8): is a tenant-less platform-admin token revoked given its
     * {@code sub} and {@code iat}? Same {@code iat <= revocationTime} (SAFE) boundary as {@link #isRevoked}. Consulted
     * by both the admin resource-server chain (per-request) and {@code /oauth2/introspect}.
     *
     * @param platformAdminId the token's {@code sub} as a platform-admin UUID
     * @param iatEpochSeconds the token's {@code iat} in epoch seconds
     */
    public boolean isPlatformAdminRevoked(UUID platformAdminId, long iatEpochSeconds) {
        if (platformAdminId == null) {
            return false;
        }
        OptionalLong revocation = markerStore.platformAdminRevocationEpochSeconds(platformAdminId);
        return revocation.isPresent() && iatEpochSeconds <= revocation.getAsLong();
    }
}
