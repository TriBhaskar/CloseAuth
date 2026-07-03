package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.dto.ClientView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.service.ResourceServerService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.UUID;

/**
 * Registers new OAuth2 clients and wires the 3c-i {@code autoCreateForClient} trigger.
 *
 * <p>This is the client-registration flow that the Stage 7 admin API will call. It builds a SAS
 * {@link RegisteredClient} carrying the tenant id as a client setting (so the tenant-aware repository writes the
 * {@code tenant_id} column), persists it, then auto-creates its 1:1 Resource Server — all in ONE transaction, so
 * a client and its RS are created atomically (a client without its auto-RS would be a broken state).
 *
 * <p><b>New-vs-update discrimination:</b> auto-creation is triggered <em>here</em> (new-client registration), NOT
 * inside {@code RegisteredClientRepository.save()}. So a plain {@code save()} of an existing client (an update)
 * never re-triggers auto-creation — only {@link #registerClient} does, and it only ever creates new clients.
 */
@Service
@RequiredArgsConstructor
public class ClientRegistrationService {

    private final RegisteredClientRepository registeredClientRepository;
    private final ResourceServerService resourceServerService;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;
    private final PasswordEncoder passwordEncoder;
    private final CloseAuthProperties properties;

    @Transactional
    public ClientView registerClient(TenantContext context, RegisterClientCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);

        RegisteredClient registeredClient = buildRegisteredClient(context, command);
        registeredClientRepository.save(registeredClient);

        // Trigger the 3c-i capability: create the client's 1:1 Resource Server (same transaction → atomic).
        resourceServerService.autoCreateForClient(context, registeredClient.getId(), registeredClient.getClientName());

        return ClientView.from(registeredClient);
    }

    private RegisteredClient buildRegisteredClient(TenantContext context, RegisterClientCommand command) {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(command.clientId())
                .clientName(command.clientName());

        if (command.clientSecret() != null && !command.clientSecret().isBlank()) {
            builder.clientSecret(passwordEncoder.encode(command.clientSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE); // public client
        }

        command.grantTypes().forEach(grant -> builder.authorizationGrantType(new AuthorizationGrantType(grant)));
        if (!CollectionUtils.isEmpty(command.scopes())) {
            command.scopes().forEach(builder::scope);
        }
        if (!CollectionUtils.isEmpty(command.redirectUris())) {
            command.redirectUris().forEach(builder::redirectUri);
        }

        ClientSettings.Builder clientSettings = ClientSettings.builder()
                .requireProofKey(command.requireProofKey())
                .requireAuthorizationConsent(false);
        CloseAuthClientSettings.withTenantId(clientSettings, context.tenantId());
        builder.clientSettings(clientSettings.build());

        builder.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(properties.getToken().getAccessTokenTtl())
                // reuseRefreshTokens=false makes SAS issue a NEW refresh token on each refresh — this is what
                // enables rotation at the SAS level (4b-i). Replaces the SAS default (reuse=true, 60-min).
                .refreshTokenTimeToLive(properties.getToken().getRefreshTokenTtl())
                .reuseRefreshTokens(false)
                .build());

        return builder.build();
    }
}
