package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
