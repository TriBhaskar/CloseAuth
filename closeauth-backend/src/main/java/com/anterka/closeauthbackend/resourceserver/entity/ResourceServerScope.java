package com.anterka.closeauthbackend.resourceserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single scope in a {@link ResourceServer}'s catalog (Section 7.6). Within the
 * ResourceServer aggregate, so the back-reference is a JPA {@code @ManyToOne}.
 * {@code scopeName} is stored BARE (prefix applied at token issuance). Maps
 * {@code resource_server_scopes}.
 */
@Entity
@Table(name = "resource_server_scopes")
@Getter
@Setter
public class ResourceServerScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Within-aggregate back-reference to the owning resource server. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_server_id", nullable = false)
    private ResourceServer resourceServer;

    @Column(name = "scope_name", nullable = false, length = 100)
    private String scopeName;

    @Column
    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "requires_consent", nullable = false)
    private boolean requiresConsent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResourceServerScope other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
