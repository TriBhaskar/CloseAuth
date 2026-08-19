package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.ClientSecretView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.common.exception.ClientNotFoundException;
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
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class),
                Mockito.mock(TenantAwareRegisteredClientRepository.class));
    }

    // ---- registration: secret generation (UI-3c) --------------------------

    @Test
    void registeringConfidentialClientPersistsWithTenantAndTriggersAutoCreate() {
        var command = new RegisterClientCommand("todomaster-spa", "TodoMaster SPA", false,
                List.of("client_credentials"), List.of("read"), null, null, false, true);

        ClientCreatedView result = service.registerClient(ctx, command);

        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(clientCaptor.capture());
        RegisteredClient saved = clientCaptor.getValue();

        // tenant id rides on the client settings (how the tenant-aware repo populates the tenant_id column)
        assertThat(CloseAuthClientSettings.getTenantId(saved)).isEqualTo(TENANT);
        assertThat(saved.getClientId()).isEqualTo("todomaster-spa");
        assertThat(saved.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(saved.getClientSecret()).startsWith("{bcrypt}"); // encoded, never raw
        assertThat(saved.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(java.time.Duration.ofMinutes(5));

        // the 3c-i capability is triggered for the new client, tenant-aware, with its SAS id
        verify(resourceServerService).autoCreateForClient(eq(ctx), eq(saved.getId()), eq("TodoMaster SPA"));

        // the ONE-TIME plaintext secret in the response is what got encoded — never blank, never the encoded form
        assertThat(result.clientSecret()).isNotBlank();
        assertThat(saved.getClientSecret()).isEqualTo("{bcrypt}" + result.clientSecret());
    }

    @Test
    void registerClientCommandHasNoSecretField_backendIsTheOnlySource() {
        // RegisterClientCommand no longer carries a clientSecret field at all (removed in UI-3c) — the backend
        // is structurally the only source of the plaintext, not merely "ignoring" a caller-supplied one. Two
        // otherwise-identical registrations still get two DIFFERENT generated secrets, proving genuine randomness
        // rather than a fixed/derived value.
        var command = new RegisterClientCommand("app-a", "App A", false,
                List.of("client_credentials"), null, null, null, false, true);
        var command2 = new RegisterClientCommand("app-b", "App B", false,
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
        var command = new RegisterClientCommand("public-spa", "Public SPA", true,
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
                Mockito.mock(PasswordEncoder.class), new ClientSecretGenerator(),
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties(),
                Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class), tenantAware);

        assertThat(withRealCount.countClients(ctx)).isEqualTo(5L);
    }

    // ---- FE-4c: post-logout URIs, secret-rotation metadata ----------------

    @Test
    void registeringClientPersistsPostLogoutUris() {
        var command = new RegisterClientCommand("web-app", "Web App", false,
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
        var command = new RegisterClientCommand("todomaster-spa", "TodoMaster SPA", false,
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
}
