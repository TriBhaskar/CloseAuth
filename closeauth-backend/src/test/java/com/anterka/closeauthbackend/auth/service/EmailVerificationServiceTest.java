package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService.VerificationOutcome;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private OneTimeTokenService oneTimeTokenService;
    private RedisRateLimiter rateLimiter;
    private AuthNotificationSender notifier;
    private UserService userService;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        oneTimeTokenService = Mockito.mock(OneTimeTokenService.class);
        rateLimiter = Mockito.mock(RedisRateLimiter.class);
        notifier = Mockito.mock(AuthNotificationSender.class);
        userService = Mockito.mock(UserService.class);
        service = new EmailVerificationService(oneTimeTokenService, rateLimiter, notifier, userService,
                new CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void verifySuccessMarksEmailVerifiedAndActivatesPendingUser() {
        when(oneTimeTokenService.consumeCode(eq("123456"), eq(OneTimeTokenPurpose.EMAIL_VERIFICATION), eq(tenantId), any()))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.EMAIL_VERIFICATION,
                        tenantId, userId, "a@x.com", null));
        when(userService.getUserById(ctx, userId)).thenReturn(user(UserStatus.PENDING));

        assertThat(service.verify(ctx, "a@x.com", "123456")).isEqualTo(VerificationOutcome.VERIFIED);
        verify(userService).markEmailVerified(ctx, userId);
        verify(userService).activateUser(ctx, userId); // PENDING → ACTIVE
    }

    @Test
    void verifyDoesNotActivateAnAlreadyActiveUser() {
        when(oneTimeTokenService.consumeCode(any(), any(), any(), any()))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.EMAIL_VERIFICATION,
                        tenantId, userId, "a@x.com", null));
        when(userService.getUserById(ctx, userId)).thenReturn(user(UserStatus.ACTIVE));

        assertThat(service.verify(ctx, "a@x.com", "123456")).isEqualTo(VerificationOutcome.VERIFIED);
        verify(userService).markEmailVerified(ctx, userId);
        verify(userService, never()).activateUser(any(), any());
    }

    @Test
    void invalidCodeReturnsGenericFailure() {
        when(oneTimeTokenService.consumeCode(any(), any(), any(), any()))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.EXPIRED));
        assertThat(service.verify(ctx, "a@x.com", "000000")).isEqualTo(VerificationOutcome.INVALID);
        verify(userService, never()).markEmailVerified(any(), any());
    }

    @Test
    void attemptLockoutStopsBruteForceBeforeConsuming() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(false); // locked
        assertThat(service.verify(ctx, "a@x.com", "123456")).isEqualTo(VerificationOutcome.RATE_LIMITED);
        verify(oneTimeTokenService, never()).consumeCode(any(), any(), any(), any());
    }

    @Test
    void issuanceRateLimitDropsSilently() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(false);
        service.requestVerification(ctx, userId, "a@x.com");
        verify(oneTimeTokenService, never()).issue(any());
        verify(notifier, never()).sendEmailVerificationCode(any(), any());
    }

    private UserView user(UserStatus status) {
        return new UserView(userId, tenantId, "a@x.com", false, null, false, "F", "L", status,
                null, Instant.now(), Instant.now());
    }
}
