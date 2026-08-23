package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.ClientSecretView;
import com.anterka.closeauthbackend.client.dto.ClientView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.exception.ClientNotFoundException;
import com.anterka.closeauthbackend.common.exception.ClientPublicNoSecretException;
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

import java.time.Instant;
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
 *
 * <p><b>UI-3c: the secret is generated here, never accepted from the caller.</b> {@link RegisterClientCommand} no
 * longer carries a {@code clientSecret} field at all — see {@link ClientSecretGenerator}'s javadoc for why. This
 * service is also, as of UI-3c, the one place a confidential client's secret can be replaced after creation
 * ({@link #regenerateClientSecret}), since CloseAuth previously had no recovery path for a lost secret at all.
 *
 * <p><b>Post-FE-4c: the OAuth2 {@code client_id} is generated here too</b> ({@link ClientIdGenerator}), for the
 * same reason the secret is — {@link RegisterClientCommand} no longer carries a {@code clientId} field at all.
 */
@Service
@RequiredArgsConstructor
public class ClientRegistrationService {

    private final RegisteredClientRepository registeredClientRepository;
    private final ResourceServerService resourceServerService;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;
    private final PasswordEncoder passwordEncoder;
    private final ClientSecretGenerator clientSecretGenerator;
    private final ClientIdGenerator clientIdGenerator;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;
    // FE-4d: the concrete type, not the RegisteredClientRepository interface above — Spring resolves this to the
    // SAME singleton bean (TenantAwareRegisteredClientRepository implements RegisteredClientRepository), just typed
    // narrowly enough to reach countByTenantId, which isn't part of SAS's own interface.
    private final TenantAwareRegisteredClientRepository tenantAwareRegisteredClientRepository;

    @Transactional
    public ClientCreatedView registerClient(TenantContext context, RegisterClientCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);

        String clientId = clientIdGenerator.generate(command.clientName(), candidate ->
                tenantAwareRegisteredClientRepository.existsByTenantIdAndClientId(context.tenantId(), candidate));

        String rawSecret = command.publicClient() ? null : clientSecretGenerator.generate();
        RegisteredClient registeredClient = buildRegisteredClient(context, command, clientId, rawSecret);
        registeredClientRepository.save(registeredClient);

        // Trigger the 3c-i capability: create the client's 1:1 Resource Server (same transaction → atomic).
        resourceServerService.autoCreateForClient(context, registeredClient.getId(), registeredClient.getClientName());

        auditEmitter.emit(AuditEvents.clientRegistered(context.tenantId(), registeredClient.getId(),
                registeredClient.getClientId()));
        // rawSecret is returned here ONCE (only the encoded hash was persisted above); ClientCreatedView is the
        // only place it ever appears. null for a public client.
        return new ClientCreatedView(ClientView.from(registeredClient), rawSecret);
    }

    /**
     * Mints a fresh secret for an existing confidential client and returns it once — the missing recovery path
     * for "the admin lost the secret" (previously the client was simply dead; there was no way back). Refuses a
     * public client outright ({@link ClientPublicNoSecretException}) rather than silently making it confidential.
     *
     * <p>{@code TenantAwareRegisteredClientRepository.save} already routes an existing client id to a plain SAS
     * {@code UPDATE} (SAS columns only; {@code tenant_id} untouched) — no repository change was needed for this.
     */
    @Transactional
    public ClientSecretView regenerateClientSecret(TenantContext context, String clientRegisteredId) {
        tenantService.requireActiveTenant(context);
        RegisteredClient existing = loadClientOrThrow(context, clientRegisteredId);

        if (existing.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new ClientPublicNoSecretException(clientRegisteredId);
        }

        String rawSecret = clientSecretGenerator.generate();
        // Rebuild ClientSettings from the existing map (preserving TENANT_ID) rather than a fresh builder, then
        // stamp/overwrite SECRET_ROTATED_AT — FE-4c's Credentials tab reads this for "last rotated."
        ClientSettings.Builder clientSettings = ClientSettings.withSettings(existing.getClientSettings().getSettings());
        CloseAuthClientSettings.withSecretRotatedAt(clientSettings, Instant.now());
        RegisteredClient rotated = RegisteredClient.from(existing)
                .clientSecret(passwordEncoder.encode(rawSecret))
                .clientSettings(clientSettings.build())
                .build();
        registeredClientRepository.save(rotated);

        auditEmitter.emit(AuditEvents.clientSecretRegenerated(context.tenantId(), rotated.getId(), rotated.getClientId()));
        return new ClientSecretView(ClientView.from(rotated), rawSecret);
    }

    /**
     * Tenant-scoped lookup shared by {@link #regenerateClientSecret} and {@code TenantClientController.get} — a
     * client that exists but belongs to a different tenant 404s exactly like one that doesn't exist at all
     * (defense in depth, never distinguish the two to a caller).
     */
    public RegisteredClient loadClientOrThrow(TenantContext context, String clientRegisteredId) {
        RegisteredClient client = registeredClientRepository.findById(clientRegisteredId);
        if (client == null || !context.tenantId().equals(CloseAuthClientSettings.getTenantId(client))) {
            throw new ClientNotFoundException(clientRegisteredId);
        }
        return client;
    }

    /** FE-4d: the overview's Clients count tile — see {@link TenantAwareRegisteredClientRepository#countByTenantId}. */
    public long countClients(TenantContext context) {
        return tenantAwareRegisteredClientRepository.countByTenantId(context.tenantId());
    }

    /**
     * FE-4.10: closes the client-list gap flagged throughout this module (see
     * {@link TenantAwareRegisteredClientRepository#findByTenantId}) — never returns a secret (same
     * {@link ClientView#from} used everywhere else). Paged in the application layer over this full-tenant list,
     * same {@link com.anterka.closeauthbackend.common.web.PageView} convention every other list endpoint uses.
     */
    @Transactional(readOnly = true)
    public List<ClientView> listClients(TenantContext context) {
        return tenantAwareRegisteredClientRepository.findByTenantId(context.tenantId()).stream()
                .map(ClientView::from)
                .toList();
    }

    private RegisteredClient buildRegisteredClient(TenantContext context, RegisterClientCommand command,
                                                    String clientId, String rawSecret) {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(command.clientName());

        if (rawSecret != null) {
            builder.clientSecret(passwordEncoder.encode(rawSecret))
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
        if (!CollectionUtils.isEmpty(command.postLogoutUris())) {
            command.postLogoutUris().forEach(builder::postLogoutRedirectUri);
        }

        ClientSettings.Builder clientSettings = ClientSettings.builder()
                .requireProofKey(command.requireProofKey())
                // Stage 6b-ii: trusted (first-party) clients skip consent; others require it. This is the per-client
                // "skip consent" flag; `requires_consent` on individual RS scopes governs auto-grant WITHIN a shown
                // consent screen (see ConsentScopeResolver / the consent auto-grant customizer).
                .requireAuthorizationConsent(!command.trusted());
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
