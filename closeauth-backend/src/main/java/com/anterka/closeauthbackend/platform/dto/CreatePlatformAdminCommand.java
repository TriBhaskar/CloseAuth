package com.anterka.closeauthbackend.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to create a platform admin (Stage 7a). Called by the bootstrap and (Stage 7b) the admin API. Password length
 * is enforced centrally by {@code PasswordHasher}; email is globally unique (checked in the service).
 */
public record CreatePlatformAdminCommand(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank String password,
        String firstName,
        String lastName) {
}
