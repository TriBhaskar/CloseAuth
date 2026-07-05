package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;

/**
 * A tenant self-registration strategy (§9.2) — one per {@link RegistrationMode}. Each composes 3b's
 * {@code UserService} (+ the one-time-token primitive as needed) to apply its mode's policy. {@code RegistrationService}
 * selects the strategy from the tenant's resolved mode. Registration is tenant-scoped (the {@code TenantContext} is
 * resolved from the authorization request's {@code client_id}, as in 6a).
 */
public interface RegistrationStrategy {

    /** The registration mode this strategy implements (its key in the dispatcher). */
    RegistrationMode mode();

    /** Registers the user under this mode's policy, within the given tenant. */
    RegistrationResult register(TenantContext context, RegisterUserCommand command);
}
