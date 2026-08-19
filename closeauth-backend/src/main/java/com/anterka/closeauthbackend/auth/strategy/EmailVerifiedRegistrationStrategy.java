package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * EMAIL_VERIFIED registration: the user self-registers as {@code PENDING} and receives an email-verification code;
 * consuming it ({@code EmailVerificationService.verify}) activates the account.
 */
@Component
@RequiredArgsConstructor
public class EmailVerifiedRegistrationStrategy implements RegistrationStrategy {

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;

    @Override
    public RegistrationMode mode() {
        return RegistrationMode.EMAIL_VERIFIED;
    }

    @Override
    public RegistrationResult register(TenantContext context, RegisterUserCommand command) {
        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), command.password(), command.firstName(), command.lastName(), command.phone(),
                UserStatus.PENDING));
        emailVerificationService.requestVerification(
                context, user.id(), command.email(), command.clientId(), command.tenantSlug());
        return new RegistrationResult(user.id(), user.status(), RegistrationMode.EMAIL_VERIFIED, true);
    }
}
