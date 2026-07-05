package com.anterka.closeauthbackend.tenant.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Command to update a tenant's branding (Stage 6b-ii capability; the admin HTTP endpoint that calls it is Stage 7).
 * All fields optional; null leaves a field unset (resolution then uses the platform default). Colors are validated as
 * strict {@code #RRGGBB} hex; {@code logoUrl} strictness (well-formed, https) is enforced in the service. Strict
 * validation on write is the first layer of the branding-injection defense (safe injection in the UI is the second).
 */
public record UpdateBrandingCommand(

        @Size(max = 500)
        String logoUrl,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a #RRGGBB hex color")
        String primaryColor,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a #RRGGBB hex color")
        String backgroundColor,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a #RRGGBB hex color")
        String accentColor,

        @Size(max = 200)
        String companyName) {
}
