package com.anterka.closeauthbackend.identity.dto;

import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.enums.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a user (output DTO, Convention 4).
 *
 * <p><b>Contains NO credential material</b> — no password hash, no algorithm, no identity secrets.
 * Credentials live on {@code UserIdentity} and never cross this boundary.
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
        Instant updatedAt
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
                user.getUpdatedAt()
        );
    }
}
