package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADMIN_APPROVED registration: the user self-registers as {@code PENDING} and stays pending until a tenant admin
 * approves them.
 *
 * <p><b>Stage 7 seam:</b> the admin-approval action (transition {@code PENDING → ACTIVE} via {@code UserService
 * .activateUser}) is exposed by the Stage 7 admin API. 6b-i creates the pending user and records the pending state;
 * it does not implement the approval trigger.
 */
@Component
@RequiredArgsConstructor
public class AdminApprovedRegistrationStrategy implements RegistrationStrategy {

    private final UserService userService;

    @Override
    public RegistrationMode mode() {
        return RegistrationMode.ADMIN_APPROVED;
    }

    @Override
    public RegistrationResult register(TenantContext context, RegisterUserCommand command) {
        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), command.password(), command.firstName(), command.lastName(), command.phone(),
                UserStatus.PENDING));
        return new RegistrationResult(user.id(), user.status(), RegistrationMode.ADMIN_APPROVED, false);
    }
}
