package com.anterka.closeauthbackend.resourceserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Command to add a scope to a resource server's catalog (input DTO).
 *
 * @param scopeName       bare scope name (e.g. {@code read}, {@code portfolio:write}); the {@code {rs_slug}:}
 *                        prefix is applied at token issuance. Unique per resource server.
 * @param description     optional human description.
 * @param isDefault       auto-granted when a client accesses this resource server.
 * @param requiresConsent whether granting this scope requires user consent.
 */
public record AddScopeCommand(

        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "^[a-z0-9]([a-z0-9:._-]*[a-z0-9])?$",
                message = "scopeName must be lowercase and may contain '.', '_', '-', ':' (not at the ends)")
        String scopeName,

        String description,

        boolean isDefault,

        boolean requiresConsent

) {}
