package com.anterka.closeauthbackend.identity.dto;

import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.enums.UserStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a user (output DTO, Convention 4).
 *
 * <p><b>Contains NO credential material</b> — no password hash, no algorithm, no identity secrets.
 * Credentials live on {@code UserIdentity} and never cross this boundary.
 *
 * @param roles {@code null} means "not computed" (this module has no dependency on {@code rbac}; callers that need
 *              this populate it via {@link #withRoles(List)} using {@code TenantRoleService}, which already depends
 *              on this module — same convention as {@code TenantView#adminCount}). Never an empty list to mean
 *              "unknown" — a populated empty list genuinely means the user holds no tenant roles.
 * @param isLastActiveAdmin {@code null} means "not computed"; populated via {@link #withIsLastActiveAdmin(boolean)}
 *                          on the single-user read only (the users list doesn't need it — its Actions column offers
 *                          navigation, not lifecycle).
 */
public record UserView(
        UUID id,
        UUID tenantId,
        String email,
        boolean emailVerified,
        String phone,
        boolean phoneVerified,
        String firstName,
        String lastName,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        List<String> roles,
        Boolean isLastActiveAdmin
) {

    /** Maps a managed {@link User} entity to an immutable, credential-free view. Call within a transaction. */
    public static UserView from(User user) {
        return new UserView(
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getPhone(),
                user.isPhoneVerified(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                null,
                null
        );
    }

    /** Returns a copy with {@code roles} populated — the merge step callers outside this module perform. */
    public UserView withRoles(List<String> roles) {
        return new UserView(id, tenantId, email, emailVerified, phone, phoneVerified, firstName, lastName, status,
                lastLoginAt, createdAt, updatedAt, roles, isLastActiveAdmin);
    }

    /** Returns a copy with {@code isLastActiveAdmin} populated. */
    public UserView withIsLastActiveAdmin(boolean isLastActiveAdmin) {
        return new UserView(id, tenantId, email, emailVerified, phone, phoneVerified, firstName, lastName, status,
                lastLoginAt, createdAt, updatedAt, roles, isLastActiveAdmin);
    }
}
