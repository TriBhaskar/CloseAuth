package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ApplicationRoleScope} (the application-role scope bundle). Analogous to
 * {@code ResourceServerScopeRepository} in 3c-i: the entity is a within-aggregate child of
 * {@code ApplicationRole}, but direct CRUD needs generated ids and by-role queries. Tenant/RS safety
 * is enforced by the service (it loads the owning application role tenant-scoped first, then scopes
 * every query to that role's id via the {@code applicationRole.id} path).
 */
@Repository
public interface ApplicationRoleScopeRepository extends JpaRepository<ApplicationRoleScope, UUID> {

    boolean existsByApplicationRole_IdAndResourceServerScopeId(UUID applicationRoleId, UUID resourceServerScopeId);

    Optional<ApplicationRoleScope> findByApplicationRole_IdAndResourceServerScopeId(
            UUID applicationRoleId, UUID resourceServerScopeId);

    List<ApplicationRoleScope> findByApplicationRole_Id(UUID applicationRoleId);

    /**
     * FE-4b (spec §6.4.4): the reverse of {@link #findByApplicationRole_Id} — for every scope bundled into at
     * least one application role of the given resource server, how many roles bundle it. One query for the
     * whole catalog (called once per resource-server-detail page load), not per scope — same "bulk read,
     * decorate the DTO at the boundary" shape as {@code UserTenantRoleRepository#findTenantRoleNamesByTenant}.
     */
    @Query(value = """
            SELECT ars.resource_server_scope_id AS scopeId, count(*) AS roleCount
              FROM application_role_scopes ars
              JOIN application_roles ar ON ar.id = ars.application_role_id
             WHERE ar.resource_server_id = :resourceServerId
             GROUP BY ars.resource_server_scope_id""", nativeQuery = true)
    List<ScopeRoleCountProjection> countRoleUsageByResourceServer(@Param("resourceServerId") UUID resourceServerId);
}
