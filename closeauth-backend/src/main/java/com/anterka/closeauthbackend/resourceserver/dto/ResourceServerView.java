package com.anterka.closeauthbackend.resourceserver.dto;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a resource server (output DTO). Scopes are fetched separately via
 * {@code listScopes}, so this view does not embed them (avoids lazy-loading surprises).
 *
 * @param scopeCount FE-4b (spec §6.4.4's list "Scope count" column) — {@code null} means "not computed"; the
 *                   list endpoint populates it via {@link #withScopeCount(long)} from a single bulk query
 *                   (never per-row), same convention as {@code TenantView#adminCount}.
 */
public record ResourceServerView(
        UUID id,
        UUID tenantId,
        String slug,
        String name,
        String audienceIdentifier,
        boolean autoCreated,
        Instant createdAt,
        Instant updatedAt,
        Long scopeCount
) {

    public static ResourceServerView from(ResourceServer rs) {
        return new ResourceServerView(
                rs.getId(),
                rs.getTenantId(),
                rs.getSlug(),
                rs.getName(),
                rs.getAudienceIdentifier(),
                rs.isAutoCreated(),
                rs.getCreatedAt(),
                rs.getUpdatedAt(),
                null
        );
    }

    /** Returns a copy with {@code scopeCount} populated — the merge step callers outside this module perform. */
    public ResourceServerView withScopeCount(long scopeCount) {
        return new ResourceServerView(id, tenantId, slug, name, audienceIdentifier, autoCreated, createdAt,
                updatedAt, scopeCount);
    }
}
