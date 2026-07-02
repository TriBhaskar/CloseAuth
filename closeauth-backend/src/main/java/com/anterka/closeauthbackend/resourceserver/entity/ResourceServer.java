package com.anterka.closeauthbackend.resourceserver.entity;

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
 * A Resource Server — a first-class, tenant-owned API that CloseAuth-issued tokens
 * target (Section 7.6). Aggregate root; owns its {@link ResourceServerScope} catalog.
 * Maps {@code resource_servers}.
 */
@Entity
@Table(name = "resource_servers")
@Getter
@Setter
public class ResourceServer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cross-aggregate reference to the owning tenant — plain UUID (Rule 2). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 63)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    /** Value placed in the token {@code aud} claim; globally unique. */
    @Column(name = "audience_identifier", nullable = false, length = 255)
    private String audienceIdentifier;

    @Column(name = "is_auto_created", nullable = false)
    private boolean autoCreated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Within-aggregate children: the RS scope catalog. */
    @OneToMany(mappedBy = "resourceServer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ResourceServerScope> scopes = new ArrayList<>();

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
        if (!(o instanceof ResourceServer other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
