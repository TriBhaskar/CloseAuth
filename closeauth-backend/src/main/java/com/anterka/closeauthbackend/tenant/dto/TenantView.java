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
 *
 * @param adminCount count of active {@code TENANT_ADMIN} holders (§1.16 of the tenant-onboarding design) —
 *                   {@code null} means "not computed" (the tenant module deliberately has no dependency on
 *                   {@code rbac}; callers that need this populate it via {@link #withAdminCount(long)} using
 *                   {@code TenantRoleService}, which already depends on this module). Never {@code 0} to mean
 *                   "unknown" — a populated {@code 0} genuinely means the tenant has no active admin.
 */
public record TenantView(
        UUID id,
        String slug,
        String name,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Long adminCount
) {

    /** Maps a managed {@link Tenant} entity to an immutable view. Call within a transaction. {@code adminCount} unset. */
    public static TenantView from(Tenant tenant) {
        return new TenantView(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                tenant.getDeletedAt(),
                null
        );
    }

    /** Returns a copy with {@code adminCount} populated — the merge step callers outside this module perform. */
    public TenantView withAdminCount(long adminCount) {
        return new TenantView(id, slug, name, status, createdAt, updatedAt, deletedAt, adminCount);
    }
}
