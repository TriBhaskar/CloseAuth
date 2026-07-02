package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * The client is already authorized for the resource server ({@code UNIQUE (client_id, resource_server_id)}).
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class ClientAuthorizationConflictException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.client_authorization_conflict";

    public ClientAuthorizationConflictException(String clientRegisteredId, UUID resourceServerId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Client is already authorized for this resource server",
                Map.of("clientRegisteredId", clientRegisteredId, "resourceServerId", resourceServerId.toString()));
    }
}
