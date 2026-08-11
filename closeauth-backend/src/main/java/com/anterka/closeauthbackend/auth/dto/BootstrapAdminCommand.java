package com.anterka.closeauthbackend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to bootstrap a tenant's first {@code TENANT_ADMIN} with a system-generated temporary credential (Phase 3
 * of the tenant-onboarding design, {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} §2.4/§2.9). Deliberately carries
 * NO password field — the temporary password is always server-generated (decision 6), never caller-supplied,
 * mirroring {@code RegisterClientCommand} no longer accepting a caller-chosen client secret.
 *
 * @param email     the new admin's email; normalized and uniqueness-checked by {@code UserService.createUserWithPassword}.
 * @param firstName optional.
 * @param lastName  optional.
 */
public record BootstrapAdminCommand(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName

) {}
