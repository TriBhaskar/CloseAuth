package com.anterka.closeauthbackend.resourceserver.dto;

import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a client→resource-server authorization (output DTO).
 *
 * @param clientRegisteredId the SAS {@code oauth2_registered_client(id)} PK (a String).
 * @param authorizedScopes   comma-delimited allowed scope names; {@code null} means "all scopes".
 */
public record ClientAuthorizationView(
        UUID id,
        String clientRegisteredId,
        UUID resourceServerId,
        String authorizedScopes,
        Instant createdAt
) {

    public static ClientAuthorizationView from(ClientAuthorizedResourceServer link) {
        return new ClientAuthorizationView(
                link.getId(),
                link.getClientRegisteredId(),
                link.getResourceServerId(),
                link.getAuthorizedScopes(),
                link.getCreatedAt()
        );
    }
}
