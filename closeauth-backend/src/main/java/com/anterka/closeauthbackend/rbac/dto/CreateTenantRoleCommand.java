package com.anterka.closeauthbackend.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to create a custom tenant role ({@code is_system = false}). System roles are created by the
 * starter-pack callback, not here.
 */
public record CreateTenantRoleCommand(

        @NotBlank
        @Size(max = 50)
        String name,

        @Size(max = 2000)
        String description,

        boolean isDefault

) {}
