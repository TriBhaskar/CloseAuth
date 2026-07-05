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
 * OPEN registration: the user self-registers and is immediately {@code ACTIVE} — no verification or approval gate.
 */
@Component
@RequiredArgsConstructor
public class OpenRegistrationStrategy implements RegistrationStrategy {

    private final UserService userService;

    @Override
    public RegistrationMode mode() {
        return RegistrationMode.OPEN;
    }

    @Override
    public RegistrationResult register(TenantContext context, RegisterUserCommand command) {
        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), command.password(), command.firstName(), command.lastName(), command.phone(),
                UserStatus.ACTIVE));
        return new RegistrationResult(user.id(), user.status(), RegistrationMode.OPEN, false);
    }
}
