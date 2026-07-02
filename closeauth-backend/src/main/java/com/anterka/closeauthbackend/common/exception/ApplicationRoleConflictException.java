package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * An application role name is already used for the resource server ({@code UNIQUE (resource_server_id, name)}).
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class ApplicationRoleConflictException extends CloseAuthDomainException {

    private static final String CODE = "application_role.name_conflict";

    public ApplicationRoleConflictException(UUID resourceServerId, String name) {
        super(ErrorCategory.CONFLICT, CODE,
                "Application role name already in use for this resource server: " + name,
                Map.of("resourceServerId", resourceServerId.toString(), "name", name));
    }
}
