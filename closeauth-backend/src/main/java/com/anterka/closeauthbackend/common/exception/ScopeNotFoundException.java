package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * A scope could not be found under the given resource server. Category {@link ErrorCategory#NOT_FOUND}.
 */
public class ScopeNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.scope_not_found";

    public ScopeNotFoundException(UUID resourceServerId, UUID scopeId) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "Scope " + scopeId + " not found under resource server " + resourceServerId,
                Map.of("resourceServerId", resourceServerId.toString(), "scopeId", scopeId.toString()));
    }
}
