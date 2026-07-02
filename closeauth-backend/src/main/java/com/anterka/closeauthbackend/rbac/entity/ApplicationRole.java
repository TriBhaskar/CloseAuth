package com.anterka.closeauthbackend.rbac.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An application (Resource-Server-scoped) role (Section 7.9). Aggregate root; owns
 * its scope bundle ({@link ApplicationRoleScope}) as within-aggregate children.
 * The role's Resource Server is a different aggregate, so {@code resourceServerId}
 * is a plain UUID (Rule 1). Maps {@code application_roles}.
 */
@Entity
@Table(name = "application_roles")
@Getter
@Setter
public class ApplicationRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cross-aggregate reference to the owning resource server — plain UUID. */
    @Column(name = "resource_server_id", nullable = false)
    private UUID resourceServerId;

    /** Cross-aggregate reference to the tenant — plain UUID (Rule 2), denormalized. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column
    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Within-aggregate children: the named bundle of scopes this role grants. */
    @OneToMany(mappedBy = "applicationRole", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ApplicationRoleScope> scopes = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApplicationRole other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
