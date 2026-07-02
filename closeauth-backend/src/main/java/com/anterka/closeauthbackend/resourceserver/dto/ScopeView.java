package com.anterka.closeauthbackend.resourceserver.dto;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a resource server scope (output DTO). The owning resource-server id is passed in by the
 * caller (the service already knows it) rather than navigated through the lazy {@code @ManyToOne}.
 */
public record ScopeView(
        UUID id,
        UUID resourceServerId,
        String scopeName,
        String description,
        boolean isDefault,
        boolean requiresConsent,
        Instant createdAt
) {

    public static ScopeView from(ResourceServerScope scope, UUID resourceServerId) {
        return new ScopeView(
                scope.getId(),
                resourceServerId,
                scope.getScopeName(),
                scope.getDescription(),
                scope.isDefault(),
                scope.isRequiresConsent(),
                scope.getCreatedAt()
        );
    }
}
