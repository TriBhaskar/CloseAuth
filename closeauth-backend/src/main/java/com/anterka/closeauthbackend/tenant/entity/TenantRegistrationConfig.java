package com.anterka.closeauthbackend.tenant.entity;

import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
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
 * Per-tenant registration configuration (§9.2). 1:1 with a tenant. Maps {@code tenant_registration_config}. Carries the
 * registration mode today; structured to accrue further registration policy later without a rewrite, e.g.:
 * allowed email domains, default role on registration, auto-approve conditions, and an
 * <b>enumeration-safe-registration</b> flag (whether {@code POST /register} reveals "email already registered" (409,
 * the platform default) or returns a uniform response — deferred to this per-tenant flag rather than a platform-wide
 * change; see the Stage 6b-i report §9/D4). {@code tenantId} is a plain UUID cross-aggregate reference (Rule 2).
 */
@Entity
@Table(name = "tenant_registration_config")
@Getter
@Setter
public class TenantRegistrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistrationMode mode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

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
        if (!(o instanceof TenantRegistrationConfig other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
