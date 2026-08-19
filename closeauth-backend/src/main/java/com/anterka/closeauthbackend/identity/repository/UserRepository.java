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

    /**
     * FE-4a: server-side filtering for the tenant users list (spec §6.4.2 — status/role/search live in the URL, so
     * a filtered view is shareable at any tenant size; a client-side filter over a capped page would misrepresent
     * what it's filtering once a tenant holds more users than one page). Every filter is optional — pass
     * {@code null} to skip it. {@code roleName} matches via {@code EXISTS} against the same
     * {@code user_tenant_roles}/{@code tenant_roles} join {@link com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository}
     * already uses; {@code searchPattern} is a pre-wrapped {@code %term%} the caller builds (lower-cased) so the SQL
     * itself does no string concatenation.
     */
    @Query(value = """
            SELECT u.* FROM users u
             WHERE u.tenant_id = :tenantId
               AND (:status IS NULL OR u.status = :status)
               AND (:searchPattern IS NULL
                    OR LOWER(u.email) LIKE :searchPattern
                    OR LOWER(u.first_name) LIKE :searchPattern
                    OR LOWER(u.last_name) LIKE :searchPattern)
               AND (:roleName IS NULL OR EXISTS (
                    SELECT 1 FROM user_tenant_roles utr
                      JOIN tenant_roles tr ON tr.id = utr.tenant_role_id
                     WHERE utr.user_id = u.id AND utr.tenant_id = u.tenant_id AND tr.name = :roleName))
             ORDER BY u.created_at DESC""", nativeQuery = true)
    List<User> findByTenantIdFiltered(@Param("tenantId") UUID tenantId,
                                       @Param("status") String status,
                                       @Param("roleName") String roleName,
                                       @Param("searchPattern") String searchPattern);
}
