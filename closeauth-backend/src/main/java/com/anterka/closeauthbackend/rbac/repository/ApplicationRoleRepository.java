package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link ApplicationRole} aggregate root (its scope bundle is
 * reached through it). Role names are unique per Resource Server.
 */
@Repository
public interface ApplicationRoleRepository extends JpaRepository<ApplicationRole, UUID> {

    List<ApplicationRole> findByResourceServerId(UUID resourceServerId);

    Optional<ApplicationRole> findByResourceServerIdAndName(UUID resourceServerId, String name);

    /** Tenant-scoped load (for assignment / scope-bundle ops that key only by role id + ctx). */
    Optional<ApplicationRole> findByIdAndTenantId(UUID id, UUID tenantId);

    /** RS-scoped load (for RS-keyed CRUD, after the RS itself was loaded tenant-scoped). */
    Optional<ApplicationRole> findByIdAndResourceServerId(UUID id, UUID resourceServerId);

    List<ApplicationRole> findByTenantId(UUID tenantId);
}
