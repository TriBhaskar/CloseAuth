package com.anterka.closeauthbackend.resourceserver.dto;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a resource server (output DTO). Scopes are fetched separately via
 * {@code listScopes}, so this view does not embed them (avoids lazy-loading surprises).
 */
public record ResourceServerView(
        UUID id,
        UUID tenantId,
        String slug,
        String name,
        String audienceIdentifier,
        boolean autoCreated,
        Instant createdAt,
        Instant updatedAt
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
                rs.getUpdatedAt()
        );
    }
}
