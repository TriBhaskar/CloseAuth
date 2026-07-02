package com.anterka.closeauthbackend.token.repository;

import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
