package com.anterka.closeauthbackend.rbac.dto;

import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;

import java.time.Instant;
import java.util.UUID;

/** Read view of an application (Resource-Server-scoped) role (output DTO). */
public record ApplicationRoleView(
        UUID id,
        UUID resourceServerId,
        UUID tenantId,
        String name,
        String description,
        boolean isDefault,
        boolean isSystem,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApplicationRoleView from(ApplicationRole role) {
        return new ApplicationRoleView(
                role.getId(),
                role.getResourceServerId(),
                role.getTenantId(),
                role.getName(),
                role.getDescription(),
                role.isDefault(),
                role.isSystem(),
                role.getCreatedAt(),
                role.getUpdatedAt()
        );
    }
}
