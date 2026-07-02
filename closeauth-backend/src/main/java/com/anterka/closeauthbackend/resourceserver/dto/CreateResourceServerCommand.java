package com.anterka.closeauthbackend.resourceserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Command to explicitly create a standalone resource server (input DTO). The auto-created 1:1 path is
 * {@code autoCreateForClient}, not this.
 *
 * @param slug                DNS-safe slug, unique within the tenant; used as the scope prefix at issuance.
 * @param name                display name.
 * @param audienceIdentifier  the value placed in the token {@code aud} claim; globally unique and
 *                            <b>immutable after creation</b>.
 */
public record CreateResourceServerCommand(

        @NotBlank
        @Size(max = 63)
        @Pattern(
                regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "slug must be lowercase alphanumeric and hyphens, not starting or ending with a hyphen")
        String slug,

        @NotBlank
        @Size(max = 200)
        String name,

        @NotBlank
        @Size(max = 255)
        String audienceIdentifier

) {}
