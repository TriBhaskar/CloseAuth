package com.anterka.closeauthbackend.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's consent authorizing an {@link Agent} to hold scopes against a Resource
 * Server (Section 7.10). Its own aggregate root (Rule 3): all references, including
 * to the agent, are plain UUIDs. Maps {@code agent_consent_grants}.
 */
@Entity
@Table(name = "agent_consent_grants")
@Getter
@Setter
public class AgentConsentGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "granting_user_id", nullable = false)
    private UUID grantingUserId;

    @Column(name = "resource_server_id", nullable = false)
    private UUID resourceServerId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Space-delimited scope names. */
    @Column(name = "granted_scopes", nullable = false)
    private String grantedScopes;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentConsentGrant other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
