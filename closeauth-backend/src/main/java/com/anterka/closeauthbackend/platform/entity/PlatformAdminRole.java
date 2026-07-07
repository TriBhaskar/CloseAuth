package com.anterka.closeauthbackend.platform.entity;

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
 * Assigns a platform role ({@code platform_roles}, V1/V2) to a {@link PlatformAdmin} (§7.9). This replaces
 * {@code user_platform_roles} for platform admins (that table FKs {@code users}, which is wrong for the separate
 * platform-admins entity). Cross-aggregate references are plain UUIDs (Rule 2), resolved via the repository — never an
 * object-graph walk. Maps {@code platform_admin_roles}.
 */
@Entity
@Table(name = "platform_admin_roles")
@Getter
@Setter
public class PlatformAdminRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "platform_admin_id", nullable = false)
    private UUID platformAdminId;

    @Column(name = "platform_role_id", nullable = false)
    private UUID platformRoleId;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by_platform_admin_id")
    private UUID grantedByPlatformAdminId;

    @PrePersist
    void onCreate() {
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlatformAdminRole other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
