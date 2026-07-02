package com.anterka.closeauthbackend.client.dto;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read view of a registered client (output DTO). Contains NO client secret.
 */
public record ClientView(
        String id,
        String clientId,
        String clientName,
        UUID tenantId,
        Set<String> grantTypes,
        Set<String> scopes,
        Set<String> redirectUris
) {

    public static ClientView from(RegisteredClient client) {
        return new ClientView(
                client.getId(),
                client.getClientId(),
                client.getClientName(),
                CloseAuthClientSettings.getTenantId(client),
                client.getAuthorizationGrantTypes().stream()
                        .map(AuthorizationGrantType::getValue).collect(Collectors.toSet()),
                client.getScopes(),
                client.getRedirectUris()
        );
    }
}
