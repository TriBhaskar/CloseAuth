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

    /**
     * Per-tenant count of active {@code roleName} holders, across ALL tenants in one query (§1.16 of
     * {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}) — backs the platform tenant list's "has an admin" signal
     * (caller passes {@code SystemRoleNames.TENANT_ADMIN}, kept a bind parameter rather than hardcoded here so the
     * two can never drift apart). Additive alongside {@link #countActiveHoldersByTenantAndRole}, which is
     * single-tenant-scoped and used by the last-admin invariant; this is the cross-tenant sibling the tenant list
     * needs, avoiding an N+1 over tenants. A tenant with zero active holders is simply absent from the result —
     * there is no zero-count row.
     */
    @Query(value = """
            SELECT utr.tenant_id AS tenantId, count(*) AS adminCount
              FROM user_tenant_roles utr
              JOIN tenant_roles tr ON tr.id = utr.tenant_role_id
              JOIN users u ON u.id = utr.user_id
             WHERE tr.name = :roleName AND u.status = 'ACTIVE'
             GROUP BY utr.tenant_id""", nativeQuery = true)
    List<TenantAdminCountProjection> countActiveAdminsPerTenant(@Param("roleName") String roleName);
}
