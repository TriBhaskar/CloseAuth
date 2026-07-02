package com.anterka.closeauthbackend.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Command to provision a new tenant (Convention 4 — input DTO, distinct from the
 * output {@link TenantView}).
 *
 * <p>Structural validation is expressed as Jakarta Bean Validation annotations
 * (Convention 5). These are enforced at the HTTP boundary in Stage 7 via {@code @Valid};
 * business rules (slug uniqueness) live in the service, not here.
 *
 * @param slug DNS-safe tenant slug: lowercase alphanumerics and hyphens, not starting or
 *             ending with a hyphen, 1–63 chars (matching the {@code VARCHAR(63)} column).
 * @param name human-readable display name.
 */
public record ProvisionTenantCommand(

        @NotBlank
        @Size(max = 63)
        @Pattern(
                regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "slug must be lowercase alphanumeric and hyphens, not starting or ending with a hyphen")
        String slug,

        @NotBlank
        @Size(max = 200)
        String name

) {}
