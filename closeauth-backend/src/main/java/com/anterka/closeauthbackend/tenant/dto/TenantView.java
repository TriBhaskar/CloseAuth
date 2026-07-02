package com.anterka.closeauthbackend.tenant.dto;

import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a tenant (Convention 4 — output DTO). Services return this, never the
 * {@link Tenant} JPA entity, so no lazy proxy or persistence semantics leak to callers.
 *
 * <p>Mapping is a hand-written static factory ({@link #from(Tenant)}); no mapping library
 * is used (Convention 4).
 */
public record TenantView(
        UUID id,
        String slug,
        String name,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    /** Maps a managed {@link Tenant} entity to an immutable view. Call within a transaction. */
    public static TenantView from(Tenant tenant) {
        return new TenantView(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                tenant.getDeletedAt()
        );
    }
}
