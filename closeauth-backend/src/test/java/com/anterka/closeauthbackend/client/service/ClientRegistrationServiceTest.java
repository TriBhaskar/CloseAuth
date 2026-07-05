package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.resourceserver.service.ResourceServerService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientRegistrationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
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
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}$2a$12$encoded");
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        service = new ClientRegistrationService(registeredClientRepository, resourceServerService,
                tenantService, commandValidator, passwordEncoder,
                new com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties());
    }

    @Test
    void registeringNewClientPersistsWithTenantAndTriggersAutoCreate() {
        var command = new RegisterClientCommand("todomaster-spa", "TodoMaster SPA", "secret-value",
                List.of("client_credentials"), List.of("read"), null, false, true);

        service.registerClient(ctx, command);

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
    }
}
