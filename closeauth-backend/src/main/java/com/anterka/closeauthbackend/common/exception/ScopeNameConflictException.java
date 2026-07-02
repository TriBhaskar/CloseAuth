package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * A scope name is already defined for the resource server ({@code UNIQUE (resource_server_id, scope_name)}).
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class ScopeNameConflictException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.scope_conflict";

    public ScopeNameConflictException(UUID resourceServerId, String scopeName) {
        super(ErrorCategory.CONFLICT, CODE,
                "Scope already defined for resource server: " + scopeName,
                Map.of("resourceServerId", resourceServerId.toString(), "scopeName", scopeName));
    }
}
