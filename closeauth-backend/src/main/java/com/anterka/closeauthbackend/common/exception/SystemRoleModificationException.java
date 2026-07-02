package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * An attempt to modify or delete a system ({@code is_system = true}) role. System roles are
 * CloseAuth-provided and immutable; only their assignments to users can change. Category
 * {@link ErrorCategory#FORBIDDEN}.
 */
public class SystemRoleModificationException extends CloseAuthDomainException {

    private static final String CODE = "role.system_immutable";

    public SystemRoleModificationException(String roleName) {
        super(ErrorCategory.FORBIDDEN, CODE,
                "System role cannot be modified or deleted: " + roleName,
                Map.of("name", roleName));
    }
}
