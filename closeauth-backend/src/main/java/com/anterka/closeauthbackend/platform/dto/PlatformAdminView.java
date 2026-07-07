package com.anterka.closeauthbackend.platform.dto;

import com.anterka.closeauthbackend.platform.entity.PlatformAdmin;
import com.anterka.closeauthbackend.platform.enums.PlatformAdminStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a platform admin (Convention 4 — output DTO). Contains NO credential material (no hash/algo). Roles are
 * resolved separately ({@code PlatformAdminService.resolveRoleNames}).
 */
public record PlatformAdminView(
        UUID id,
        String email,
        PlatformAdminStatus status,
        String firstName,
        String lastName,
        Instant lastLoginAt,
        Instant createdAt) {

    public static PlatformAdminView from(PlatformAdmin admin) {
        return new PlatformAdminView(admin.getId(), admin.getEmail(), admin.getStatus(),
                admin.getFirstName(), admin.getLastName(), admin.getLastLoginAt(), admin.getCreatedAt());
    }
}
