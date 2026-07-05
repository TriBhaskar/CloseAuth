package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.OneTimeTokenService;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteOnlyRegistrationStrategyTest {

    private final UUID tenantId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private UserService userService;
    private OneTimeTokenService oneTimeTokenService;
    private InviteOnlyRegistrationStrategy strategy;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        oneTimeTokenService = Mockito.mock(OneTimeTokenService.class);
        strategy = new InviteOnlyRegistrationStrategy(userService, oneTimeTokenService);
    }

    @Test
    void refusesWithoutAnInviteToken() {
        assertThatThrownBy(() -> strategy.register(ctx, command(null)))
                .isInstanceOf(CloseAuthDomainException.class)
                .satisfies(e -> assertThat(((CloseAuthDomainException) e).getCategory()).isEqualTo(ErrorCategory.FORBIDDEN));
        verify(userService, never()).createUserWithPassword(any(), any());
    }

    @Test
    void refusesWhenInviteIsInvalid() {
        when(oneTimeTokenService.consume("inv", OneTimeTokenPurpose.INVITE, tenantId))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.NOT_FOUND));
        assertThatThrownBy(() -> strategy.register(ctx, command("inv")))
                .isInstanceOf(CloseAuthDomainException.class);
        verify(userService, never()).createUserWithPassword(any(), any());
    }

    @Test
    void refusesWhenInviteWasForADifferentEmail() {
        when(oneTimeTokenService.consume("inv", OneTimeTokenPurpose.INVITE, tenantId))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.INVITE, tenantId,
                        UUID.randomUUID(), "someone-else@x.com", null));
        assertThatThrownBy(() -> strategy.register(ctx, command("inv")))
                .isInstanceOf(CloseAuthDomainException.class);
        verify(userService, never()).createUserWithPassword(any(), any());
    }

    @Test
    void createsActiveUserWhenInviteValidAndEmailMatches() {
        UUID newUserId = UUID.randomUUID();
        when(oneTimeTokenService.consume("inv", OneTimeTokenPurpose.INVITE, tenantId))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.INVITE, tenantId,
                        UUID.randomUUID(), "a@x.com", null));
        when(userService.createUserWithPassword(any(), any(CreateUserWithPasswordCommand.class)))
                .thenReturn(new UserView(newUserId, tenantId, "a@x.com", false, null, false, "F", "L",
                        UserStatus.ACTIVE, null, Instant.now(), Instant.now()));

        var result = strategy.register(ctx, command("inv"));

        assertThat(result.userId()).isEqualTo(newUserId);
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE); // invited users are created ACTIVE
        verify(userService).createUserWithPassword(any(), any());
    }

    private RegisterUserCommand command(String inviteToken) {
        return new RegisterUserCommand("a@x.com", "password123", "F", "L", null, inviteToken);
    }
}
