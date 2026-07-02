package com.anterka.closeauthbackend.rbac.dto;

import com.anterka.closeauthbackend.rbac.entity.PlatformRole;

import java.time.Instant;
import java.util.UUID;

/** Read view of a platform role (output DTO). */
public record PlatformRoleView(UUID id, String name, String description, Instant createdAt) {

    public static PlatformRoleView from(PlatformRole role) {
        return new PlatformRoleView(role.getId(), role.getName(), role.getDescription(), role.getCreatedAt());
    }
}
