package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A client could not be found within the path tenant. Deliberately fired both for a genuinely nonexistent client
 * id AND for a client that exists but belongs to a different tenant (defense in depth: never distinguish the two,
 * so a client id can't be used to probe cross-tenant existence). Category {@link ErrorCategory#NOT_FOUND}.
 *
 * <p>UI-3c: extracted from the inline {@code CloseAuthDomainException} that {@code TenantClientController.get}
 * used to throw directly, so {@code ClientRegistrationService.regenerateClientSecret} can share the exact same
 * tenant-scoped lookup instead of re-implementing it.
 */
public class ClientNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "client.not_found";

    public ClientNotFoundException(String clientId) {
        super(ErrorCategory.NOT_FOUND, CODE, "Client not found: " + clientId,
                Map.of("clientId", clientId));
    }
}
