package com.anterka.closeauthbackend.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Command to create an application role under a resource server ({@code is_system = false}). */
public record CreateApplicationRoleCommand(

        @NotBlank
        @Size(max = 50)
        String name,

        @Size(max = 2000)
        String description,

        boolean isDefault

) {}
