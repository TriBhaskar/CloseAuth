package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the auth-flow tenant resolution — the spine of cross-tenant-safe authentication: a {@code client_id}
 * resolves to exactly its owning tenant, and an unknown client resolves to no tenant (so auth cannot proceed globally).
 */
class AuthFlowTenantResolverTest {

    private final UUID tenantId = UUID.randomUUID();

    private RegisteredClientRepository registeredClientRepository;
    private AuthFlowTenantResolver resolver;

    @BeforeEach
    void setUp() {
        registeredClientRepository = Mockito.mock(RegisteredClientRepository.class);
        resolver = new AuthFlowTenantResolver(registeredClientRepository);
    }

    @Test
    void resolvesTheOwningTenantOfAClient() {
        when(registeredClientRepository.findByClientId("acme-app")).thenReturn(clientWithTenant(tenantId));
        assertThat(resolver.resolveTenantId("acme-app")).contains(tenantId);
    }

    @Test
    void unknownClientResolvesToNoTenant() {
        when(registeredClientRepository.findByClientId("nope")).thenReturn(null);
        assertThat(resolver.resolveTenantId("nope")).isEmpty();
    }

    @Test
    void blankClientIdResolvesToNoTenant() {
        assertThat(resolver.resolveTenantId("")).isEmpty();
        assertThat(resolver.resolveTenantId(null)).isEmpty();
    }

    private RegisteredClient clientWithTenant(UUID tenant) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("acme-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://acme.example/callback")
                .scope("openid")
                .clientSettings(CloseAuthClientSettings.withTenantId(ClientSettings.builder(), tenant).build())
                .build();
    }
}
