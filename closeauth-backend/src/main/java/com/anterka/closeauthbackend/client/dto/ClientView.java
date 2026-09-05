package com.anterka.closeauthbackend.client.dto;

import com.anterka.closeauthbackend.client.service.AdminConsoleClientProvisioningCallback;
import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Instant;
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
 *
 * <p><b>FE-4c additions: {@code createdAt}, {@code secretRotatedAt}, {@code postLogoutRedirectUris}.</b>
 * {@code createdAt} is free — SAS already tracks it via {@link RegisteredClient#getClientIdIssuedAt()}.
 * {@code secretRotatedAt} is {@code null} until the first {@link
 * com.anterka.closeauthbackend.client.service.ClientRegistrationService#regenerateClientSecret} call — see {@link
 * CloseAuthClientSettings#getSecretRotatedAt}.
 *
 * <p><b>Client update/delete additions: {@code requireProofKey}, {@code trusted}, {@code platformManaged}.</b>
 * The first two are free — SAS already tracks both as {@link org.springframework.security.oauth2.server.authorization.settings.ClientSettings}
 * booleans, and the console's edit form needs them to seed itself. {@code platformManaged} is {@code true} for
 * the tenant's auto-provisioned {@code admin-console-{slug}} client (see
 * {@link AdminConsoleClientProvisioningCallback}) — the same "never offer an action the backend would refuse"
 * invariant {@code publicClient} serves above: {@code ClientRegistrationService.updateClient}/{@code
 * deleteClient} both 409 on this client ({@code client.platform_managed}), so the console must know to hide
 * both controls rather than let an admin discover the refusal by clicking.
 */
public record ClientView(
        String id,
        String clientId,
        String clientName,
        UUID tenantId,
        boolean publicClient,
        Set<String> grantTypes,
        Set<String> scopes,
        Set<String> redirectUris,
        Set<String> postLogoutRedirectUris,
        Instant createdAt,
        Instant secretRotatedAt,
        boolean requireProofKey,
        boolean trusted,
        boolean platformManaged
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
                client.getRedirectUris(),
                client.getPostLogoutRedirectUris(),
                client.getClientIdIssuedAt(),
                CloseAuthClientSettings.getSecretRotatedAt(client),
                client.getClientSettings().isRequireProofKey(),
                !client.getClientSettings().isRequireAuthorizationConsent(),
                client.getClientId().startsWith(AdminConsoleClientProvisioningCallback.CLIENT_ID_PREFIX)
        );
    }
}
