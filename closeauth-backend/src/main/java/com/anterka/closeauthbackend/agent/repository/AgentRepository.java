package com.anterka.closeauthbackend.agent.repository;

import com.anterka.closeauthbackend.agent.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link Agent} aggregate root. Schema only in Phase 1; the agent
 * feature lands in Phase 4. All lookups are tenant-scoped.
 */
@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByTenantId(UUID tenantId);

    Optional<Agent> findByTenantIdAndName(UUID tenantId, String name);

    @Query("SELECT a FROM Agent a WHERE a.id = :id AND a.tenantId = :tenantId")
    Optional<Agent> findByIdInTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
