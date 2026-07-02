package com.anterka.closeauthbackend.resourceserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Command to update a resource server's mutable fields (full replacement of the mutable set).
 *
 * <p><b>{@code audience_identifier} is intentionally absent — it is immutable after creation</b> (a stable
 * contract with resource servers, like {@code sub} stability). {@code slug} is mutable (a rename is cheap:
 * scope names are stored bare, so no scope rows are rewritten).
 */
public record UpdateResourceServerCommand(

        @NotBlank
        @Size(max = 200)
        String name,

        @NotBlank
        @Size(max = 63)
        @Pattern(
                regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "slug must be lowercase alphanumeric and hyphens, not starting or ending with a hyphen")
        String slug

) {}
