package com.anterka.closeauthbackend.rbac.dto;

import jakarta.validation.constraints.Size;

/**
 * Command to update a custom tenant role's mutable fields. The role {@code name} is immutable (it is
 * referenced by assignments and, for system roles, by invariant checks); system roles reject updates entirely.
 */
public record UpdateTenantRoleCommand(

        @Size(max = 2000)
        String description,

        boolean isDefault

) {}
