package com.anterka.closeauthbackend.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Command for an authenticated self-service password change (input DTO). The caller proves account
 * ownership by supplying the current password; the reset-without-current path is Stage 6.
 *
 * @param userId          the user changing their password (within the caller's tenant scope).
 * @param currentPassword the existing password, verified before the change.
 * @param newPassword     the replacement; capped at 72 bytes (bcrypt input limit).
 */
public record ChangePasswordCommand(

        @NotNull
        UUID userId,

        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 72)
        String newPassword

) {}
