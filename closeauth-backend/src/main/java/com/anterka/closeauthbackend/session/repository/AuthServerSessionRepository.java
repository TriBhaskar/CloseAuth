package com.anterka.closeauthbackend.session.repository;

import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link AuthServerSession} aggregate root (the durable session
 * ledger).
 *
 * <p>{@code findBySessionKey} is legitimately tenant-unscoped: {@code session_key} is
 * the globally-unique opaque cookie value that self-identifies the session. The
 * user/tenant listing supports the "list my active sessions" endpoint (Section 7.5).
 */
@Repository
public interface AuthServerSessionRepository extends JpaRepository<AuthServerSession, UUID> {

    /** Global by design: session_key is the globally-unique opaque cookie value. */
    Optional<AuthServerSession> findBySessionKey(String sessionKey);

    List<AuthServerSession> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    /** Active (not-yet-revoked) sessions for a user within a tenant — backs listing and bulk user-session revocation. */
    List<AuthServerSession> findByUserIdAndTenantIdAndRevokedAtIsNull(UUID userId, UUID tenantId);

    /**
     * Best-effort ledger bookkeeping on a successful validation: advance {@code last_accessed_at} and the slid idle
     * expiry. Self-transactional so a non-transactional {@code validateSession} can call it and tolerate its failure
     * without affecting the (Redis-authoritative) validity decision. Returns rows updated (0 if already revoked).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AuthServerSession s SET s.lastAccessedAt = :now, s.idleExpiresAt = :idleExpiresAt
            WHERE s.sessionKey = :sessionKey AND s.revokedAt IS NULL
            """)
    int touchOnValidation(@Param("sessionKey") String sessionKey,
                          @Param("now") Instant now,
                          @Param("idleExpiresAt") Instant idleExpiresAt);
}
