package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * Direct deletion of an auto-created resource server is refused: an auto-created RS's lifecycle is tied
 * 1:1 to its client, so it is removed by deleting the client (Stage 4/7), not the RS directly. Category
 * {@link ErrorCategory#CONFLICT}.
 */
public class ResourceServerDeletionNotAllowedException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.deletion_not_allowed";

    public ResourceServerDeletionNotAllowedException(UUID resourceServerId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Auto-created resource server cannot be deleted directly; delete its client instead: " + resourceServerId,
                Map.of("resourceServerId", resourceServerId.toString()));
    }
}
