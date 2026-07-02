package com.anterka.closeauthbackend.identity.repository;

import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.enums.IdpType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link User} aggregate root (its {@code UserIdentity} children
 * are reached through it). Every lookup is tenant-scoped: {@code users.email} is only
 * unique per-tenant, so there is deliberately no tenant-unscoped {@code findByEmail}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("SELECT u FROM User u WHERE u.id = :userId AND u.tenantId = :tenantId")
    Optional<User> findByIdInTenant(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.tenantId = :tenantId")
    Optional<User> findByEmailInTenant(@Param("email") String email, @Param("tenantId") UUID tenantId);

    @Query("""
            SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END
            FROM User u WHERE u.email = :email AND u.tenantId = :tenantId
            """)
    boolean existsByEmailInTenant(@Param("email") String email, @Param("tenantId") UUID tenantId);

    /** Resolve a user via one of its credential identities, scoped to the tenant. */
    @Query("""
            SELECT u FROM User u JOIN u.identities i
            WHERE i.idpType = :idpType AND i.idpSubject = :idpSubject AND u.tenantId = :tenantId
            """)
    Optional<User> findByIdentityInTenant(@Param("tenantId") UUID tenantId,
                                          @Param("idpType") IdpType idpType,
                                          @Param("idpSubject") String idpSubject);

    List<User> findByTenantId(UUID tenantId);
}
