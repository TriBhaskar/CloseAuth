package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * The server could not generate a unique {@code client_id} after exhausting its collision-retry budget
 * ({@link com.anterka.closeauthbackend.client.service.ClientIdGenerator}). {@code client_id} is now server-derived
 * (no longer operator-typed) — this is not an operator-facing "that id is taken" error, just a near-unreachable
 * exhaustion of the random-suffix retry loop. Category {@link ErrorCategory#CONFLICT}.
 */
public class ClientIdConflictException extends CloseAuthDomainException {

    private static final String CODE = "client.id_conflict";

    public ClientIdConflictException(String lastAttemptedBody) {
        super(ErrorCategory.CONFLICT, CODE,
                "Could not generate a unique client_id: " + lastAttemptedBody,
                Map.of("clientId", lastAttemptedBody));
    }
}
