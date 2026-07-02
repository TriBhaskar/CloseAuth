package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link TenantRole} aggregate root. All lookups are tenant-scoped:
 * role names are unique per tenant, not globally.
 */
@Repository
public interface TenantRoleRepository extends JpaRepository<TenantRole, UUID> {

    Optional<TenantRole> findByTenantIdAndName(UUID tenantId, String name);

    /** Tenant-scoped load: the role must belong to the given tenant. */
    Optional<TenantRole> findByIdAndTenantId(UUID id, UUID tenantId);

    List<TenantRole> findByTenantId(UUID tenantId);

    /** Default roles auto-assigned to new users in a tenant (onboarding). */
    List<TenantRole> findByTenantIdAndIsDefaultTrue(UUID tenantId);
}
