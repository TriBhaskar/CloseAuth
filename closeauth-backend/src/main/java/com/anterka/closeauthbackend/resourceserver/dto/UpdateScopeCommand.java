package com.anterka.closeauthbackend.resourceserver.dto;

/**
 * Command to update a scope's mutable fields.
 *
 * <p><b>{@code scope_name} is intentionally absent — it is immutable</b>: it is referenced by application
 * roles (3c-ii) and appears in issued tokens, so renaming it would break both.
 */
public record UpdateScopeCommand(

        String description,

        boolean isDefault,

        boolean requiresConsent

) {}
