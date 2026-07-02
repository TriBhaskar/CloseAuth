package com.anterka.closeauthbackend.agent.entity;

import com.anterka.closeauthbackend.agent.enums.AgentStatus;
import com.anterka.closeauthbackend.agent.enums.AgentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A first-class AI agent principal (Section 7.10). Schema only in Phase 1 (empty in
 * MVP). Aggregate root. Maps {@code agents}.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cross-aggregate reference to the owning tenant — plain UUID (Rule 2). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", nullable = false, length = 50)
    private AgentType agentType;

    /** Cross-aggregate reference — the responsible party who registered the agent. */
    @Column(name = "agent_owner_user_id", nullable = false)
    private UUID agentOwnerUserId;

    /** Cross-aggregate reference — the user this agent acts on behalf of (nullable). */
    @Column(name = "represented_user_id")
    private UUID representedUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = AgentStatus.ACTIVE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Agent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
