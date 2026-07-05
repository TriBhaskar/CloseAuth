package com.anterka.closeauthbackend.tenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Per-tenant hosted-page branding (§7.1). 1:1 with a tenant. Maps {@code tenant_branding}. Structured, validated
 * fields ONLY — deliberately no raw custom CSS (injection risk; see the migration). All fields except the tenant link
 * are nullable and fall back to platform defaults at resolution. {@code tenantId} is a plain UUID cross-aggregate
 * reference (Rule 2).
 */
@Entity
@Table(name = "tenant_branding")
@Getter
@Setter
public class TenantBranding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "primary_color", length = 7)
    private String primaryColor;

    @Column(name = "background_color", length = 7)
    private String backgroundColor;

    @Column(name = "accent_color", length = 7)
    private String accentColor;

    @Column(name = "company_name", length = 200)
    private String companyName;

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
        if (!(o instanceof TenantBranding other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
