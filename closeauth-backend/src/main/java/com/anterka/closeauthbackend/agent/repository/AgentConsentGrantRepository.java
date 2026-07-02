package com.anterka.closeauthbackend.agent.repository;

import com.anterka.closeauthbackend.agent.entity.AgentConsentGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the {@link AgentConsentGrant} aggregate root. Schema only in Phase 1;
 * the consent flow lands in Phase 4.
 */
@Repository
public interface AgentConsentGrantRepository extends JpaRepository<AgentConsentGrant, UUID> {

    List<AgentConsentGrant> findByAgentId(UUID agentId);

    List<AgentConsentGrant> findByGrantingUserId(UUID grantingUserId);
}
