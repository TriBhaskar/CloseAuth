package com.anterka.closeauthbackend.rbac.dto;

import jakarta.validation.constraints.Size;

/** Command to update an application role's mutable fields ({@code name} is immutable). */
public record UpdateApplicationRoleCommand(

        @Size(max = 2000)
        String description,

        boolean isDefault

) {}
