package com.anterka.closeauthbackend.resourceserver.repository;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ResourceServerScope}. Stage 2 mapped scopes as a within-aggregate {@code @OneToMany}
 * on {@code ResourceServer} and created no repository; 3c-i adds this one for direct scope CRUD (to obtain
 * generated ids immediately and to query scopes by their owning RS). Tenant-safety is preserved by the
 * service: it first loads the owning resource server tenant-scoped, then scopes every scope query to that
 * RS's id (the {@code resourceServer.id} path below).
 */
@Repository
public interface ResourceServerScopeRepository extends JpaRepository<ResourceServerScope, UUID> {

    boolean existsByResourceServer_IdAndScopeName(UUID resourceServerId, String scopeName);

    Optional<ResourceServerScope> findByIdAndResourceServer_Id(UUID id, UUID resourceServerId);

    List<ResourceServerScope> findByResourceServer_Id(UUID resourceServerId);

    /** Resolve a bare scope name within its RS (6b-ii consent: description + requires_consent for a requested scope). */
    Optional<ResourceServerScope> findByResourceServer_IdAndScopeName(UUID resourceServerId, String scopeName);

    /**
     * FE-4b (spec §6.4.4's resource-servers list "Scope count" column): every resource server's scope count, in
     * one query for the whole tenant — not per row. Same "bulk read, decorate the DTO at the boundary" shape as
     * {@code UserTenantRoleRepository#findTenantRoleNamesByTenant}.
     */
    @Query(value = """
            SELECT rss.resource_server_id AS resourceServerId, count(*) AS scopeCount
              FROM resource_server_scopes rss
              JOIN resource_servers rs ON rs.id = rss.resource_server_id
             WHERE rs.tenant_id = :tenantId
             GROUP BY rss.resource_server_id""", nativeQuery = true)
    List<ResourceServerScopeCountProjection> countScopesByTenant(@Param("tenantId") UUID tenantId);
}
