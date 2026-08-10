package com.anterka.closeauthbackend.client.dto;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read view of a registered client (output DTO). Contains NO client secret.
 *
 * <p><b>UI-3c addition: {@code publicClient}.</b> Without it, the admin console has no way to tell — from a plain
 * {@code GET} — whether a client has a secret to regenerate at all, which is needed to honor the standing "never
 * offer an action the backend would refuse" invariant (a public client's regenerate attempt 409s with
 * {@code client.public_no_secret}). Derived the same way {@link
 * com.anterka.closeauthbackend.client.service.ClientRegistrationService#regenerateClientSecret} checks it: the
 * client's authentication methods contain {@link ClientAuthenticationMethod#NONE}.
 */
public record ClientView(
        String id,
        String clientId,
        String clientName,
        UUID tenantId,
        boolean publicClient,
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
                client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE),
                client.getAuthorizationGrantTypes().stream()
                        .map(AuthorizationGrantType::getValue).collect(Collectors.toSet()),
                client.getScopes(),
                client.getRedirectUris()
        );
    }
}
