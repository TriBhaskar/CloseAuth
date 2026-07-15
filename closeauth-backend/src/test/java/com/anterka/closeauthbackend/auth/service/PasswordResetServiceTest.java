package com.anterka.closeauthbackend.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.PasswordResetService.ResetOutcome;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private OneTimeTokenService oneTimeTokenService;
    private RedisRateLimiter rateLimiter;
    private AuthNotificationSender notifier;
    private UserService userService;
    private AuthServerSessionService sessionService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        oneTimeTokenService = Mockito.mock(OneTimeTokenService.class);
        rateLimiter = Mockito.mock(RedisRateLimiter.class);
        notifier = Mockito.mock(AuthNotificationSender.class);
        userService = Mockito.mock(UserService.class);
        sessionService = Mockito.mock(AuthServerSessionService.class);
        service = new PasswordResetService(oneTimeTokenService, rateLimiter, notifier, userService, sessionService,
                new CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void requestForUnknownEmailIsANoOpButExternallyIdentical() {
        when(userService.existsByEmail(ctx, "ghost@x.com")).thenReturn(false);
        service.requestReset(ctx, "ghost@x.com", "client-1");
        verify(oneTimeTokenService, never()).issue(any());
        verify(notifier, never()).sendPasswordResetLink(any(), any());
    }

    @Test
    void requestForKnownEmailInvalidatesPriorTokensAndSendsFreshLink() {
        when(userService.existsByEmail(ctx, "a@x.com")).thenReturn(true);
        when(userService.getUserByEmail(ctx, "a@x.com")).thenReturn(user());
        when(oneTimeTokenService.issue(any())).thenReturn(new RawOneTimeToken("raw", UUID.randomUUID(), Instant.now()));

        service.requestReset(ctx, "a@x.com", "client-1");

        verify(oneTimeTokenService).invalidateForTarget(OneTimeTokenPurpose.PASSWORD_RESET, tenantId, "a@x.com");
        verify(oneTimeTokenService).issue(any());
        verify(notifier).sendPasswordResetLink(eq("a@x.com"), any());
    }

    @Test
    void resetSetsPasswordAndRunsTheFullRevocationCascade() {
        when(oneTimeTokenService.consume(eq("reset-token"), eq(OneTimeTokenPurpose.PASSWORD_RESET), eq(tenantId)))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.PASSWORD_RESET,
                        tenantId, userId, "a@x.com", null));

        assertThat(service.resetPassword(ctx, "reset-token", "new-password-123")).isEqualTo(ResetOutcome.RESET);
        verify(userService).resetLocalPassword(ctx, userId, "new-password-123");
        // Post-reset cascade (§13.4): every existing session + refresh family + access token is revoked.
        verify(sessionService).revokeAllUserSessions(tenantId, userId);
    }

    @Test
    void resetWithInvalidTokenIsGenericAndTouchesNothing() {
        when(oneTimeTokenService.consume(any(), any(), any()))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.NOT_FOUND));

        assertThat(service.resetPassword(ctx, "bad", "new-password-123")).isEqualTo(ResetOutcome.INVALID);
        verify(userService, never()).resetLocalPassword(any(), any(), any());
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
    }

    @Test
    void deliveryFailureStillReturnsUniformlyAndNeverLogsTheLink() {
        // Enumeration-safety under SMTP outage: an existing-account request whose email send FAILS must behave
        // identically to the unknown-email case (no exception escapes → the controller returns a uniform 200).
        String secret = "RESET-LINK-SECRET-def";
        when(userService.existsByEmail(ctx, "a@x.com")).thenReturn(true);
        when(userService.getUserByEmail(ctx, "a@x.com")).thenReturn(user());
        when(oneTimeTokenService.issue(any())).thenReturn(new RawOneTimeToken(secret, UUID.randomUUID(), Instant.now()));
        doThrow(new NotificationDeliveryException("PASSWORD_RESET", "a@x.com", new RuntimeException("smtp down")))
                .when(notifier).sendPasswordResetLink(any(), any());

        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatCode(() -> service.requestReset(ctx, "a@x.com", "client-1")).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(logs);
        }

        verify(notifier).sendPasswordResetLink(eq("a@x.com"), any()); // delivery WAS attempted
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(secret)); // link/token never logged
    }

    private UserView user() {
        return new UserView(userId, tenantId, "a@x.com", true, null, false, "F", "L", UserStatus.ACTIVE,
                null, Instant.now(), Instant.now());
    }
}
