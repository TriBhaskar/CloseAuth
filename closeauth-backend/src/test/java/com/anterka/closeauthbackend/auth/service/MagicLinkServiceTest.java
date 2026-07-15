package com.anterka.closeauthbackend.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.MagicLinkService.MagicLinkAuthResult;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
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

class MagicLinkServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private OneTimeTokenService oneTimeTokenService;
    private RedisRateLimiter rateLimiter;
    private AuthNotificationSender notifier;
    private UserService userService;
    private LoginPolicyService loginPolicyService;
    private MagicLinkService service;

    @BeforeEach
    void setUp() {
        oneTimeTokenService = Mockito.mock(OneTimeTokenService.class);
        rateLimiter = Mockito.mock(RedisRateLimiter.class);
        notifier = Mockito.mock(AuthNotificationSender.class);
        userService = Mockito.mock(UserService.class);
        loginPolicyService = Mockito.mock(LoginPolicyService.class);
        service = new MagicLinkService(oneTimeTokenService, rateLimiter, notifier, userService, loginPolicyService,
                new CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void requestForUnknownEmailIsANoOp() {
        when(userService.existsByEmail(ctx, "ghost@x.com")).thenReturn(false);
        service.requestMagicLink(ctx, "ghost@x.com", "client-1");
        verify(oneTimeTokenService, never()).issue(any());
        verify(notifier, never()).sendMagicLink(any(), any());
    }

    @Test
    void requestForKnownEmailIssuesAndSendsLink() {
        when(userService.existsByEmail(ctx, "a@x.com")).thenReturn(true);
        when(userService.getUserByEmail(ctx, "a@x.com")).thenReturn(user());
        when(oneTimeTokenService.issue(any())).thenReturn(new RawOneTimeToken("raw", UUID.randomUUID(), Instant.now()));

        service.requestMagicLink(ctx, "a@x.com", "client-1");
        verify(notifier).sendMagicLink(eq("a@x.com"), any());
    }

    @Test
    void consumeAuthenticatesWhenTokenValidAndLoginAllowed() {
        when(oneTimeTokenService.consume("tok", OneTimeTokenPurpose.MAGIC_LINK, tenantId))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.MAGIC_LINK, tenantId, userId,
                        "a@x.com", null));
        when(loginPolicyService.isLoginAllowed(tenantId, userId)).thenReturn(true);

        MagicLinkAuthResult result = service.consume("tok", tenantId);
        assertThat(result.authenticated()).isTrue();
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void consumeDeniedWhenLoginPolicyRefuses() {
        when(oneTimeTokenService.consume(any(), any(), any()))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.MAGIC_LINK, tenantId, userId,
                        "a@x.com", null));
        when(loginPolicyService.isLoginAllowed(tenantId, userId)).thenReturn(false); // suspended tenant/user

        assertThat(service.consume("tok", tenantId).authenticated()).isFalse();
    }

    @Test
    void consumeDeniedWhenTokenInvalid() {
        when(oneTimeTokenService.consume(any(), any(), any()))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.EXPIRED));
        assertThat(service.consume("tok", tenantId).authenticated()).isFalse();
        verify(loginPolicyService, never()).isLoginAllowed(any(), any());
    }

    @Test
    void deliveryFailureStillReturnsUniformlyAndNeverLogsTheLink() {
        // Enumeration-safety under SMTP outage: an existing-account request whose email send FAILS must behave
        // identically to the unknown-email case (no exception escapes → the controller returns a uniform 200).
        String secret = "MAGIC-LINK-SECRET-abc";
        when(userService.existsByEmail(ctx, "a@x.com")).thenReturn(true);
        when(userService.getUserByEmail(ctx, "a@x.com")).thenReturn(user());
        when(oneTimeTokenService.issue(any())).thenReturn(new RawOneTimeToken(secret, UUID.randomUUID(), Instant.now()));
        doThrow(new NotificationDeliveryException("MAGIC_LINK", "a@x.com", new RuntimeException("smtp down")))
                .when(notifier).sendMagicLink(any(), any());

        Logger logger = (Logger) LoggerFactory.getLogger(MagicLinkService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatCode(() -> service.requestMagicLink(ctx, "a@x.com", "client-1")).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(logs);
        }

        verify(notifier).sendMagicLink(eq("a@x.com"), any()); // delivery WAS attempted
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(secret)); // link/token never logged
    }

    private UserView user() {
        return new UserView(userId, tenantId, "a@x.com", true, null, false, "F", "L", UserStatus.ACTIVE,
                null, Instant.now(), Instant.now());
    }
}
