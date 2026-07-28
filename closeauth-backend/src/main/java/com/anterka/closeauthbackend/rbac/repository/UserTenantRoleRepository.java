package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link UserTenantRole} join entity. Tenant-scoped throughout;
 * {@code countByTenantIdAndTenantRoleId} backs the "never remove the last admin"
 * invariant (Section 7.1).
 */
@Repository
public interface UserTenantRoleRepository extends JpaRepository<UserTenantRole, UUID> {

    List<UserTenantRole> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    List<UserTenantRole> findByTenantIdAndTenantRoleId(UUID tenantId, UUID tenantRoleId);

    Optional<UserTenantRole> findByUserIdAndTenantIdAndTenantRoleId(UUID userId, UUID tenantId, UUID tenantRoleId);

    long countByTenantIdAndTenantRoleId(UUID tenantId, UUID tenantRoleId);

    /**
     * Number of role-holders in the tenant whose user is {@code ACTIVE}. Backs the last-admin invariant, which must
     * count only admins who can actually authenticate — a {@code SUSPENDED}/{@code DELETED} holder's dormant assignment
     * must NOT count toward "the tenant still has an admin" (the role row is retained, but the user can't log in).
     */
    @Query(value = """
            SELECT count(*) FROM user_tenant_roles utr
              JOIN users u ON u.id = utr.user_id
             WHERE utr.tenant_id = :tenantId AND utr.tenant_role_id = :roleId AND u.status = 'ACTIVE'""",
            nativeQuery = true)
    long countActiveHoldersByTenantAndRole(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

    /** True iff {@code userId} holds the role in the tenant AND is {@code ACTIVE} (an admin that can authenticate). */
    @Query(value = """
            SELECT EXISTS(
                SELECT 1 FROM user_tenant_roles utr
                  JOIN users u ON u.id = utr.user_id
                 WHERE utr.user_id = :userId AND utr.tenant_id = :tenantId
                   AND utr.tenant_role_id = :roleId AND u.status = 'ACTIVE')""",
            nativeQuery = true)
    boolean isActiveHolder(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);
}
