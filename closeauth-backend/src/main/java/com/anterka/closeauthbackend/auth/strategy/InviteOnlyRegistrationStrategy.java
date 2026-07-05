package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.OneTimeTokenService;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * INVITE_ONLY registration: registration requires consuming a valid {@code INVITE} one-time token (issued by an admin
 * — issuance is Stage 7). No valid invite → registration is refused. The invite is bound to a specific email, so the
 * registration email must match the invite's target. An invited user is created {@code ACTIVE} (the admin vouched for
 * the address).
 *
 * <p>6b-i implements only the <em>consumption</em> side + the invite-token issuance <em>capability</em> on the
 * primitive; the admin trigger that issues invites is Stage 7.
 */
@Component
@RequiredArgsConstructor
public class InviteOnlyRegistrationStrategy implements RegistrationStrategy {

    private final UserService userService;
    private final OneTimeTokenService oneTimeTokenService;

    @Override
    public RegistrationMode mode() {
        return RegistrationMode.INVITE_ONLY;
    }

    @Override
    public RegistrationResult register(TenantContext context, RegisterUserCommand command) {
        if (command.inviteToken() == null || command.inviteToken().isBlank()) {
            throw refused("registration.invite_required", "A valid invitation is required to register.");
        }
        ConsumeResult invite = oneTimeTokenService.consume(
                command.inviteToken(), OneTimeTokenPurpose.INVITE, context.tenantId());
        if (!invite.success()) {
            throw refused("registration.invalid_invite", "The invitation is invalid or has expired.");
        }
        // The invite was issued for a specific email; the registration must use that address.
        if (!normalize(command.email()).equals(normalize(invite.target()))) {
            throw refused("registration.invite_email_mismatch", "The invitation was issued for a different email.");
        }
        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), command.password(), command.firstName(), command.lastName(), command.phone(),
                UserStatus.ACTIVE));
        return new RegistrationResult(user.id(), user.status(), RegistrationMode.INVITE_ONLY, false);
    }

    private CloseAuthDomainException refused(String code, String message) {
        return new CloseAuthDomainException(ErrorCategory.FORBIDDEN, code, message);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
