package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.ClientSecretView;
import com.anterka.closeauthbackend.client.dto.ClientView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.dto.UpdateClientCommand;
import com.anterka.closeauthbackend.common.exception.ClientNotFoundException;
import com.anterka.closeauthbackend.common.exception.ClientPlatformManagedException;
import com.anterka.closeauthbackend.common.exception.ClientPublicNoSecretException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.service.ResourceServerService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientRegistrationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(TENANT);

    private RegisteredClientRepository registeredClientRepository;
    private ResourceServerService resourceServerService;
    private ClientRegistrationService service;

    @BeforeEach
    void setUp() {
        registeredClientRepository = Mockito.mock(RegisteredClientRepository.class);
        resourceServerService = Mockito.mock(ResourceServerService.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
        // Not a real encoder: just prefixes the raw value so tests can tell "encoded" from "raw" apart.
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "{bcrypt}" + inv.getArgument(0));
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        service = new ClientRegistrationService(registeredClientRepository, resourceServerService,
                tenantService, commandValidator, passwordEncoder, new ClientSecretGenerator(),
                new ClientIdGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class),
                Mockito.mock(TenantAwareRegisteredClientRepository.class));
    }

    // ---- registration: secret generation (UI-3c) --------------------------

    @Test
    void registeringConfidentialClientPersistsWithTenantAndTriggersAutoCreate() {
        var command = new RegisterClientCommand("TodoMaster SPA", false,
                List.of("client_credentials"), List.of("read"), null, null, false, true);

        ClientCreatedView result = service.registerClient(ctx, command);

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        RegisteredClient saved = clientCaptor.getValue();

        // tenant id rides on the client settings (how the tenant-aware repo populates the tenant_id column)
        assertThat(CloseAuthClientSettings.getTenantId(saved)).isEqualTo(TENANT);
        // client_id is now server-generated from the name (ClientIdGenerator) — slugified body + random suffix.
        assertThat(saved.getClientId()).startsWith("todomaster-spa-");
        assertThat(saved.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(saved.getClientSecret()).startsWith("{bcrypt}"); // encoded, never raw
        assertThat(saved.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(java.time.Duration.ofMinutes(5));

        // the 3c-i capability is triggered for the new client, tenant-aware, with its SAS id
        verify(resourceServerService).autoCreateForClient(eq(ctx), eq(saved.getId()), eq("TodoMaster SPA"));

        // the ONE-TIME plaintext secret in the response is what got encoded — never blank, never the encoded form
        assertThat(result.clientSecret()).isNotBlank();
        assertThat(saved.getClientSecret()).isEqualTo("{bcrypt}" + result.clientSecret());
    }

    // ---- registration: client_id generation ----------------------------------

    @Test
    void registerClientCommandHasNoClientIdField_backendGeneratesItAndConsultsTheTenantScopedCollisionCheck() {
        // RegisterClientCommand no longer carries a clientId field at all — the backend is structurally the only
        // source of it. Force the first candidate to look "taken" (mocked tenantAwareRegisteredClientRepository)
        // and prove the service retries rather than persisting a colliding value.
        TenantAwareRegisteredClientRepository tenantAware = Mockito.mock(TenantAwareRegisteredClientRepository.class);
        when(tenantAware.existsByTenantIdAndClientId(Mockito.eq(TENANT), Mockito.anyString()))
                .thenReturn(true, false); // first candidate taken, second free
        ClientRegistrationService withCollisionCheck = new ClientRegistrationService(registeredClientRepository,
                resourceServerService, Mockito.mock(TenantService.class),
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                Mockito.mock(PasswordEncoder.class), new ClientSecretGenerator(), new ClientIdGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class), tenantAware);

        var command = new RegisterClientCommand("Retry Client", false,
                List.of("client_credentials"), null, null, null, false, true);
        withCollisionCheck.registerClient(ctx, command);

        // consulted twice: the rejected first candidate, then the accepted retry — proving the collision check
        // is real, not decorative.
        verify(tenantAware, org.mockito.Mockito.times(2))
                .existsByTenantIdAndClientId(Mockito.eq(TENANT), Mockito.anyString());
    }

    @Test
    void registerClientCommandHasNoSecretField_backendIsTheOnlySource() {
        // RegisterClientCommand no longer carries a clientSecret field at all (removed in UI-3c) — the backend
        // is structurally the only source of the plaintext, not merely "ignoring" a caller-supplied one. Two
        // otherwise-identical registrations still get two DIFFERENT generated secrets, proving genuine randomness
        // rather than a fixed/derived value.
        var command = new RegisterClientCommand("App A", false,
                List.of("client_credentials"), null, null, null, false, true);
        var command2 = new RegisterClientCommand("App B", false,
                List.of("client_credentials"), null, null, null, false, true);

        ClientCreatedView first = service.registerClient(ctx, command);
        ClientCreatedView second = service.registerClient(ctx, command2);

        assertThat(first.clientSecret()).isNotBlank();
        assertThat(second.clientSecret()).isNotBlank();
        assertThat(first.clientSecret()).isNotEqualTo(second.clientSecret());
        // 32 random bytes, unpadded URL-safe base64 -> 43 chars: a cheap floor for "this is not a short/weak value".
        assertThat(first.clientSecret().length()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void registeringPublicClient_NoSecretGeneratedAuthMethodNone() {
        var command = new RegisterClientCommand("Public SPA", true,
                List.of("authorization_code"), null, List.of("http://127.0.0.1/callback"), null, true, true);

        ClientCreatedView result = service.registerClient(ctx, command);

        assertThat(result.clientSecret()).isNull();
        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        RegisteredClient saved = clientCaptor.getValue();
        assertThat(saved.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(saved.getClientSecret()).isNull();
    }

    // ---- FE-4d: client count -----------------------------------------------

    @Test
    void countClientsDelegatesToTenantAwareRepository() {
        TenantAwareRegisteredClientRepository tenantAware = Mockito.mock(TenantAwareRegisteredClientRepository.class);
        when(tenantAware.countByTenantId(TENANT)).thenReturn(5);
        ClientRegistrationService withRealCount = new ClientRegistrationService(registeredClientRepository,
                resourceServerService, Mockito.mock(TenantService.class),
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                Mockito.mock(PasswordEncoder.class), new ClientSecretGenerator(), new ClientIdGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class), tenantAware);

        assertThat(withRealCount.countClients(ctx)).isEqualTo(5L);
    }

    // ---- FE-4.10: client list ----------------------------------------------

    @Test
    void listClientsDelegatesToTenantAwareRepositoryAndNeverExposesASecret() {
        RegisteredClient client = confidentialClient(TENANT, "{bcrypt}some-encoded-secret");
        TenantAwareRegisteredClientRepository tenantAware = Mockito.mock(TenantAwareRegisteredClientRepository.class);
        when(tenantAware.findByTenantId(TENANT)).thenReturn(List.of(client));
        ClientRegistrationService withRealList = new ClientRegistrationService(registeredClientRepository,
                resourceServerService, Mockito.mock(TenantService.class),
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                Mockito.mock(PasswordEncoder.class), new ClientSecretGenerator(), new ClientIdGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class), tenantAware);

        var result = withRealList.listClients(ctx);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(client.getId());
        // ClientView carries no secret field at all — nothing to assert null on, which is itself the guarantee.
    }

    // ---- FE-4c: post-logout URIs, secret-rotation metadata ----------------

    @Test
    void registeringClientPersistsPostLogoutUris() {
        var command = new RegisterClientCommand("Web App", false,
                List.of("authorization_code", "refresh_token"), List.of("read"),
                List.of("https://app.example.com/callback"), List.of("https://app.example.com/logged-out"),
                true, true);

        service.registerClient(ctx, command);

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        assertThat(clientCaptor.getValue().getPostLogoutRedirectUris())
                .containsExactly("https://app.example.com/logged-out");
    }

    @Test
    void newlyRegisteredClientHasNoSecretRotationTimestamp() {
        var command = new RegisterClientCommand("TodoMaster SPA", false,
                List.of("client_credentials"), List.of("read"), null, null, false, true);

        service.registerClient(ctx, command);

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        assertThat(CloseAuthClientSettings.getSecretRotatedAt(clientCaptor.getValue())).isNull();
    }

    // ---- regeneration (UI-3c) ----------------------------------------------

    @Test
    void regenerateClientSecret_RotatesSecretAndPersistsUpdateViaSave() {
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}old-encoded-secret");
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);

        ClientSecretView result = service.regenerateClientSecret(ctx, existing.getId());

        assertThat(result.clientSecret()).isNotBlank();
        assertThat(result.clientSecret()).isNotEqualTo("old-encoded-secret");

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        // save() is called with the SAME id as an update; TenantAwareRegisteredClientRepository routes an
        // existing id to a plain SAS UPDATE (verified against its source — no repository change was needed here).
        verify(registeredClientRepository).save(clientCaptor.capture());
        RegisteredClient rotated = clientCaptor.getValue();
        assertThat(rotated.getId()).isEqualTo(existing.getId());
        assertThat(rotated.getClientSecret()).isEqualTo("{bcrypt}" + result.clientSecret());
        assertThat(rotated.getClientSecret()).isNotEqualTo(existing.getClientSecret());
    }

    @Test
    void regenerateClientSecret_StampsRotationTimestampAndPreservesTenantId() {
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}old-encoded-secret");
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);
        assertThat(CloseAuthClientSettings.getSecretRotatedAt(existing)).isNull();

        service.regenerateClientSecret(ctx, existing.getId());

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        RegisteredClient rotated = clientCaptor.getValue();
        assertThat(CloseAuthClientSettings.getSecretRotatedAt(rotated)).isNotNull();
        // TENANT_ID must survive the ClientSettings rebuild — losing it here would break tenant-scoped lookup.
        assertThat(CloseAuthClientSettings.getTenantId(rotated)).isEqualTo(TENANT);
    }

    @Test
    void regenerateClientSecret_PublicClientRejectedWithoutMutatingIt() {
        RegisteredClient publicClient = publicClient(TENANT);
        when(registeredClientRepository.findById(publicClient.getId())).thenReturn(publicClient);

        assertThatThrownBy(() -> service.regenerateClientSecret(ctx, publicClient.getId()))
                .isInstanceOf(ClientPublicNoSecretException.class)
                .hasMessageContaining(publicClient.getId());

        // refused before any mutation — the public client is never saved/rewritten
        verify(registeredClientRepository, Mockito.never()).save(any());
    }

    @Test
    void regenerateClientSecret_UnknownClientId_NotFound() {
        when(registeredClientRepository.findById("does-not-exist")).thenReturn(null);

        assertThatThrownBy(() -> service.regenerateClientSecret(ctx, "does-not-exist"))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void regenerateClientSecret_ClientBelongsToDifferentTenant_NotFoundNotForbidden() {
        // Defense in depth: a client owned by another tenant 404s exactly like a nonexistent one, never a
        // distinguishable 403/error, so a client id can't be used to probe cross-tenant existence.
        RegisteredClient othersClient = confidentialClient(OTHER_TENANT, "{bcrypt}their-secret");
        when(registeredClientRepository.findById(othersClient.getId())).thenReturn(othersClient);

        assertThatThrownBy(() -> service.regenerateClientSecret(ctx, othersClient.getId()))
                .isInstanceOf(ClientNotFoundException.class);
    }

    // ---- update: full replacement of the mutable set ----------------------

    @Test
    void updateClient_ReplacesScopesAndUris_FullReplacementNotAppend() {
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}old-secret");
        // Seed it with a redirect URI/scope that the update command does NOT repeat — proving the consumer
        // overloads clear-then-repopulate rather than the single-value overloads, which only ADD.
        RegisteredClient seeded = RegisteredClient.from(existing)
                .redirectUri("https://old.example.com/callback")
                .scope("old-scope")
                .build();
        when(registeredClientRepository.findById(seeded.getId())).thenReturn(seeded);

        var command = new UpdateClientCommand("Renamed Client", List.of("new-scope"),
                List.of("https://new.example.com/callback"), List.of("https://new.example.com/logged-out"),
                true, false);
        ClientView result = service.updateClient(ctx, seeded.getId(), command);

        assertThat(result.scopes()).containsExactly("new-scope");
        assertThat(result.redirectUris()).containsExactly("https://new.example.com/callback");
        assertThat(result.postLogoutRedirectUris()).containsExactly("https://new.example.com/logged-out");
        assertThat(result.clientName()).isEqualTo("Renamed Client");

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(captor.capture());
        assertThat(captor.getValue().getScopes()).containsExactly("new-scope");
        assertThat(captor.getValue().getRedirectUris()).containsExactly("https://new.example.com/callback");
    }

    @Test
    void updateClient_EmptyListsClearThePreviousValues() {
        RegisteredClient existing = RegisteredClient.from(confidentialClient(TENANT, "{bcrypt}s"))
                .scope("read").redirectUri("https://old.example.com/callback").build();
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);

        var command = new UpdateClientCommand("Name", null, null, null, false, true);
        ClientView result = service.updateClient(ctx, existing.getId(), command);

        assertThat(result.scopes()).isEmpty();
        assertThat(result.redirectUris()).isEmpty();
        assertThat(result.postLogoutRedirectUris()).isEmpty();
    }

    @Test
    void updateClient_PreservesTenantIdAndSecretRotatedAt() {
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}s");
        ClientSettings.Builder rotatedSettings = ClientSettings.withSettings(existing.getClientSettings().getSettings());
        CloseAuthClientSettings.withSecretRotatedAt(rotatedSettings, Instant.now());
        RegisteredClient withRotation = RegisteredClient.from(existing).clientSettings(rotatedSettings.build()).build();
        when(registeredClientRepository.findById(withRotation.getId())).thenReturn(withRotation);

        var command = new UpdateClientCommand("Name", List.of("read"), null, null, false, true);
        service.updateClient(ctx, withRotation.getId(), command);

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(captor.capture());
        // Losing TENANT_ID here would break every tenant-scoped lookup for this client; losing
        // SECRET_ROTATED_AT would make the credentials tab silently forget a prior rotation.
        assertThat(CloseAuthClientSettings.getTenantId(captor.getValue())).isEqualTo(TENANT);
        assertThat(CloseAuthClientSettings.getSecretRotatedAt(captor.getValue())).isNotNull();
    }

    @Test
    void updateClient_DoesNotChangePublicClientOrGrantTypes() {
        // publicClient/grantTypes have no field on UpdateClientCommand at all — this proves the persisted
        // client's fundamental type is untouched by an update, not merely "the command didn't ask to change it".
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}s");
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);

        var command = new UpdateClientCommand("Renamed", List.of("read"), null, null, true, true);
        ClientView result = service.updateClient(ctx, existing.getId(), command);

        assertThat(result.publicClient()).isFalse();
        assertThat(result.grantTypes()).containsExactly("client_credentials");
    }

    @Test
    void updateClient_RequireProofKeyAndTrustedAreApplied() {
        RegisteredClient existing = publicClient(TENANT); // requireProofKey=true, trusted=default(false) initially
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);

        var command = new UpdateClientCommand("Name", null, List.of("http://127.0.0.1/callback"), null, false, true);
        ClientView result = service.updateClient(ctx, existing.getId(), command);

        assertThat(result.requireProofKey()).isFalse();
        assertThat(result.trusted()).isTrue();
    }

    @Test
    void updateClient_CrossTenant_NotFoundNotForbidden() {
        RegisteredClient othersClient = confidentialClient(OTHER_TENANT, "{bcrypt}their-secret");
        when(registeredClientRepository.findById(othersClient.getId())).thenReturn(othersClient);

        var command = new UpdateClientCommand("New Name", null, null, null, false, true);
        assertThatThrownBy(() -> service.updateClient(ctx, othersClient.getId(), command))
                .isInstanceOf(ClientNotFoundException.class);
        verify(registeredClientRepository, Mockito.never()).save(any());
    }

    @Test
    void updateClient_PlatformManagedClient_RefusedBeforeMutation() {
        RegisteredClient consoleClient = adminConsoleClient(TENANT);
        when(registeredClientRepository.findById(consoleClient.getId())).thenReturn(consoleClient);

        var command = new UpdateClientCommand("Hijacked Name", null, null, null, false, true);
        assertThatThrownBy(() -> service.updateClient(ctx, consoleClient.getId(), command))
                .isInstanceOf(ClientPlatformManagedException.class)
                .hasMessageContaining(consoleClient.getId());
        verify(registeredClientRepository, Mockito.never()).save(any());
    }

    // ---- delete: ordering, cross-tenant, and the platform-managed guard ---

    @Test
    void deleteClient_RemovesAutoRsAndSasTablesBeforeTheClientRow_ThenEmitsAudit() {
        RegisteredClient existing = confidentialClient(TENANT, "{bcrypt}s");
        when(registeredClientRepository.findById(existing.getId())).thenReturn(existing);
        TenantAwareRegisteredClientRepository tenantAware = Mockito.mock(TenantAwareRegisteredClientRepository.class);
        com.anterka.closeauthbackend.audit.service.AuditEmitter auditEmitter =
                Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class);
        ClientRegistrationService withMocks = new ClientRegistrationService(registeredClientRepository,
                resourceServerService, Mockito.mock(TenantService.class),
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                Mockito.mock(PasswordEncoder.class), new ClientSecretGenerator(), new ClientIdGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                auditEmitter, tenantAware);

        withMocks.deleteClient(ctx, existing.getId());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(resourceServerService, tenantAware, auditEmitter);
        order.verify(resourceServerService).deleteAutoCreatedForClient(ctx, existing.getId());
        order.verify(tenantAware).deleteSasAuthorizationsFor(existing.getId());
        order.verify(tenantAware).deleteSasConsentsFor(existing.getId());
        order.verify(tenantAware).deleteByIdAndTenantId(existing.getId(), TENANT);
        order.verify(auditEmitter).emit(any());
    }

    @Test
    void deleteClient_CrossTenant_NotFoundNotForbidden() {
        RegisteredClient othersClient = confidentialClient(OTHER_TENANT, "{bcrypt}their-secret");
        when(registeredClientRepository.findById(othersClient.getId())).thenReturn(othersClient);

        assertThatThrownBy(() -> service.deleteClient(ctx, othersClient.getId()))
                .isInstanceOf(ClientNotFoundException.class);
        verify(resourceServerService, Mockito.never()).deleteAutoCreatedForClient(any(), any());
    }

    @Test
    void deleteClient_PlatformManagedClient_RefusedBeforeMutation() {
        RegisteredClient consoleClient = adminConsoleClient(TENANT);
        when(registeredClientRepository.findById(consoleClient.getId())).thenReturn(consoleClient);

        assertThatThrownBy(() -> service.deleteClient(ctx, consoleClient.getId()))
                .isInstanceOf(ClientPlatformManagedException.class)
                .hasMessageContaining(consoleClient.getId());
        verify(resourceServerService, Mockito.never()).deleteAutoCreatedForClient(any(), any());
    }

    private static RegisteredClient confidentialClient(UUID tenantId, String encodedSecret) {
        ClientSettings.Builder settings = ClientSettings.builder();
        CloseAuthClientSettings.withTenantId(settings, tenantId);
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("confidential-" + UUID.randomUUID())
                .clientName("Confidential Client")
                .clientSecret(encodedSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(settings.build())
                .build();
    }

    private static RegisteredClient publicClient(UUID tenantId) {
        ClientSettings.Builder settings = ClientSettings.builder().requireProofKey(true);
        CloseAuthClientSettings.withTenantId(settings, tenantId);
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("public-" + UUID.randomUUID())
                .clientName("Public Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/callback")
                .clientSettings(settings.build())
                .build();
    }

    /** A tenant's auto-provisioned admin-console client — the platform-managed guard's target. */
    private static RegisteredClient adminConsoleClient(UUID tenantId) {
        ClientSettings.Builder settings = ClientSettings.builder().requireProofKey(true);
        CloseAuthClientSettings.withTenantId(settings, tenantId);
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(AdminConsoleClientProvisioningCallback.CLIENT_ID_PREFIX + "acme")
                .clientName("Admin Console — Acme")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://bff.example.com/admin/callback")
                .clientSettings(settings.build())
                .build();
    }
}
