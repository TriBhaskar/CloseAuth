package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/** A platform role could not be found by name. Category {@link ErrorCategory#NOT_FOUND}. */
public class PlatformRoleNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "platform_role.not_found";

    public PlatformRoleNotFoundException(String name) {
        super(ErrorCategory.NOT_FOUND, CODE, "Platform role not found: " + name, Map.of("name", name));
    }
}
