package com.anterka.closeauthbackend.rbac.entity;

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
 * Assigns an {@link ApplicationRole} to a user for a specific Resource Server
 * (Section 7.9). Join entity with plain UUID FK fields; {@code tenantId}
 * denormalized. Maps {@code user_application_roles}.
 */
@Entity
@Table(name = "user_application_roles")
@Getter
@Setter
public class UserApplicationRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "resource_server_id", nullable = false)
    private UUID resourceServerId;

    @Column(name = "application_role_id", nullable = false)
    private UUID applicationRoleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserApplicationRole other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
