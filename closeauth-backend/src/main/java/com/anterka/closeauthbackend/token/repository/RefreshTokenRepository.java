package com.anterka.closeauthbackend.token.repository;

import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link RefreshToken} aggregate root.
 *
 * <p>{@code findByTokenHash} is legitimately tenant-unscoped: {@code token_hash} is a
 * globally-unique, high-entropy hash presented by the caller, so it self-identifies the
 * exact row (the tenant is then read from it). Family/user lookups exist for rotation
 * and replay detection (Section 7.3).
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Global by design: token_hash is a globally-unique secret hash. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Replay detection revokes an entire rotation family. */
    List<RefreshToken> findByFamilyId(UUID familyId);

    @Query("""
            SELECT t FROM RefreshToken t
            WHERE t.userId = :userId AND t.tenantId = :tenantId AND t.status = :status
            """)
    List<RefreshToken> findByUserInTenantWithStatus(@Param("userId") UUID userId,
                                                     @Param("tenantId") UUID tenantId,
                                                     @Param("status") RefreshTokenStatus status);

    /**
     * Atomic {@code ACTIVE -> USED} transition — the concurrency primitive for rotation. Exactly one concurrent
     * request presenting the same ACTIVE token wins ({@code affected == 1}); the loser sees {@code affected == 0}
     * (a benign race, NOT a replay). Returns the number of rows updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.USED,
                                      t.lastUsedAt = :now
            WHERE t.id = :id AND t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.ACTIVE
            """)
    int markUsedIfActive(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes every token in a family (replay containment): all tokens sharing {@code familyId} that are not
     * already REVOKED become REVOKED. Returns the number of rows revoked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.REVOKED,
                                      t.revokedAt = :now
            WHERE t.familyId = :familyId
              AND t.status <> com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.REVOKED
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Revokes all of a user's refresh tokens within a tenant (admin/security capability for Stage 7/4b-ii).
     * Tenant-scoped so it can never touch another tenant. Returns the number of rows revoked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.REVOKED,
                                      t.revokedAt = :now
            WHERE t.userId = :userId AND t.tenantId = :tenantId
              AND t.status <> com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.REVOKED
            """)
    int revokeAllUserFamilies(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                              @Param("now") Instant now);

    /** Lazy expiry: mark an ACTIVE-but-past-expiry token EXPIRED on read (a sweeper job is a later concern). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.EXPIRED
            WHERE t.id = :id AND t.status = com.anterka.closeauthbackend.token.enums.RefreshTokenStatus.ACTIVE
            """)
    int markExpiredIfActive(@Param("id") UUID id);
}
