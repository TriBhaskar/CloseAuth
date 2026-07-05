package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.auth.strategy.RegistrationStrategy;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import com.anterka.closeauthbackend.tenant.service.RegistrationConfigService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the registration dispatcher selects the strategy matching the tenant's resolved mode. */
class RegistrationServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    @Test
    void dispatchesToTheStrategyForTheTenantsResolvedMode() {
        RegistrationStrategy open = strategy(RegistrationMode.OPEN);
        RegistrationStrategy emailVerified = strategy(RegistrationMode.EMAIL_VERIFIED);
        RegistrationConfigService config = Mockito.mock(RegistrationConfigService.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator validator = Mockito.mock(CommandValidator.class);
        when(config.resolveMode(tenantId)).thenReturn(RegistrationMode.EMAIL_VERIFIED);

        RegistrationService service = new RegistrationService(List.of(open, emailVerified), config, tenantService, validator);
        RegisterUserCommand command = new RegisterUserCommand("a@x.com", "password123", "F", "L", null, null);
        RegistrationResult expected = new RegistrationResult(UUID.randomUUID(), UserStatus.PENDING,
                RegistrationMode.EMAIL_VERIFIED, true);
        when(emailVerified.register(ctx, command)).thenReturn(expected);

        RegistrationResult result = service.register(ctx, command);

        assertThat(result).isSameAs(expected);
        verify(validator).validate(command);
        verify(tenantService).requireActiveTenant(ctx); // cannot register into a non-active tenant
        verify(open, never()).register(any(), any());   // only the matching mode's strategy runs
    }

    private RegistrationStrategy strategy(RegistrationMode mode) {
        RegistrationStrategy strategy = Mockito.mock(RegistrationStrategy.class);
        when(strategy.mode()).thenReturn(mode);
        return strategy;
    }
}
