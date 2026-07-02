package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * A scope was added to an application role belonging to a <em>different</em> resource server. An application
 * role for RS-A may only bundle scopes owned by RS-A (permissions-are-scopes, §7.9). Category
 * {@link ErrorCategory#VALIDATION}.
 */
public class ScopeResourceServerMismatchException extends CloseAuthDomainException {

    private static final String CODE = "application_role.scope_rs_mismatch";

    public ScopeResourceServerMismatchException(UUID roleResourceServerId, UUID scopeResourceServerId) {
        super(ErrorCategory.VALIDATION, CODE,
                "Scope belongs to a different resource server than the application role",
                Map.of("roleResourceServerId", roleResourceServerId.toString(),
                        "scopeResourceServerId", String.valueOf(scopeResourceServerId)));
    }
}
