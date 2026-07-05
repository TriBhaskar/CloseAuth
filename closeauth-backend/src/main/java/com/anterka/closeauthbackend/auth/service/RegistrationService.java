package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.auth.strategy.RegistrationStrategy;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import com.anterka.closeauthbackend.tenant.service.RegistrationConfigService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registration entry point (Stage 6b-i): validates input, requires an ACTIVE tenant, resolves the tenant's
 * registration mode, and dispatches to the matching {@link RegistrationStrategy} (the strategy pattern over the
 * reserved {@code auth/strategy/} package). Registration is tenant-scoped throughout.
 */
@Service
public class RegistrationService {

    private final Map<RegistrationMode, RegistrationStrategy> strategies = new EnumMap<>(RegistrationMode.class);
    private final RegistrationConfigService registrationConfigService;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;

    public RegistrationService(List<RegistrationStrategy> strategyBeans,
                               RegistrationConfigService registrationConfigService,
                               TenantService tenantService,
                               CommandValidator commandValidator) {
        strategyBeans.forEach(strategy -> this.strategies.put(strategy.mode(), strategy));
        this.registrationConfigService = registrationConfigService;
        this.tenantService = tenantService;
        this.commandValidator = commandValidator;
    }

    @Transactional
    public RegistrationResult register(TenantContext context, RegisterUserCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context); // cannot register into a suspended/provisioning tenant

        RegistrationMode mode = registrationConfigService.resolveMode(context.tenantId());
        RegistrationStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            // Every mode has a registered strategy; this guards a future enum value added without its strategy.
            throw new IllegalStateException("No registration strategy for mode " + mode);
        }
        return strategy.register(context, command);
    }
}
