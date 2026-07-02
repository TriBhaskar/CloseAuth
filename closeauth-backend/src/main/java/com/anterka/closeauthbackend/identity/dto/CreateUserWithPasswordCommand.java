package com.anterka.closeauthbackend.identity.dto;

import com.anterka.closeauthbackend.identity.enums.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to create a password-based user within a tenant (input DTO, Convention 4).
 *
 * @param email         the user's email; normalized (trimmed + lowercased) by the service before
 *                      the per-tenant uniqueness check and storage.
 * @param password      the raw password; hashed into a {@code LOCAL_PASSWORD} identity, never stored
 *                      on the user row. Capped at 72 bytes (bcrypt input limit).
 * @param firstName     optional.
 * @param lastName      optional.
 * @param phone         optional.
 * @param initialStatus optional initial status; {@code null} defaults to {@code PENDING}. Only
 *                      {@code PENDING} or {@code ACTIVE} are accepted (the registration-policy decision
 *                      of which to use is Stage 6's).
 */
public record CreateUserWithPasswordCommand(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 8, max = 72)
        String password,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName,

        @Size(max = 20)
        String phone,

        UserStatus initialStatus

) {}
