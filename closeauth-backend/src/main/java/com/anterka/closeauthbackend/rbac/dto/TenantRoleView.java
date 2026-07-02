package com.anterka.closeauthbackend.rbac.dto;

import com.anterka.closeauthbackend.rbac.entity.TenantRole;

import java.time.Instant;
import java.util.UUID;

/** Read view of a tenant role (output DTO). */
public record TenantRoleView(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        boolean isDefault,
        boolean isSystem,
        Instant createdAt,
        Instant updatedAt
) {

    public static TenantRoleView from(TenantRole role) {
        return new TenantRoleView(
                role.getId(),
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
