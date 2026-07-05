package com.anterka.closeauthbackend.auth.repository;

import com.anterka.closeauthbackend.auth.entity.OneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link OneTimeToken} aggregate root.
 *
 * <p>{@code findByTokenHash} is legitimately tenant-unscoped: {@code token_hash} is a globally-unique, high-entropy
 * hash presented by the caller, so it self-identifies the exact row (the tenant is then verified against it). The
 * single-use guarantee is the atomic {@link #markUsedIfUnused} conditional update — the same race discipline as
 * 4b-i's refresh-token replay detection.
 */
@Repository
public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    /** Global by design: token_hash is a globally-unique secret hash. */
    Optional<OneTimeToken> findByTokenHash(String tokenHash);

    /**
     * Atomic single-use transition: marks the token used only if it is currently unused. Exactly one concurrent
     * consumer wins ({@code affected == 1}); a replay / concurrent loser sees {@code affected == 0}. Returns rows
     * updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OneTimeToken t SET t.used = true, t.usedAt = :now
            WHERE t.id = :id AND t.used = false
            """)
    int markUsedIfUnused(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Invalidates all outstanding (unused) tokens of a purpose for a target — e.g. issuing a new password-reset token
     * invalidates prior unused ones (single active reset token per user is good hygiene). Returns rows invalidated.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OneTimeToken t SET t.used = true, t.usedAt = :now
            WHERE t.purpose = :purpose AND t.tenantId = :tenantId AND t.target = :target AND t.used = false
            """)
    int invalidateForTarget(@Param("purpose") OneTimeTokenPurpose purpose,
                            @Param("tenantId") UUID tenantId,
                            @Param("target") String target,
                            @Param("now") Instant now);
}
