package com.anterka.closeauthbackend.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService.VerificationOutcome;
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

import java.time.Duration;
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
    void aNotFoundOrWrongPurposeOrWrongTenantCodeCollapsesToTheGenericInvalidOutcome() {
        // FE-2d: EXPIRED/ALREADY_USED get their own outcomes below — everything else still collapses, exactly as
        // the primitive's own ConsumeResult javadoc requires (no further enumeration of "why").
        for (ConsumeResult.FailureReason reason : new ConsumeResult.FailureReason[] {
                ConsumeResult.FailureReason.NOT_FOUND,
                ConsumeResult.FailureReason.WRONG_PURPOSE,
                ConsumeResult.FailureReason.WRONG_TENANT,
        }) {
            when(oneTimeTokenService.consumeCode(any(), any(), any(), any())).thenReturn(ConsumeResult.failure(reason));
            assertThat(service.verify(ctx, "a@x.com", "000000")).isEqualTo(VerificationOutcome.INVALID);
        }
        verify(userService, never()).markEmailVerified(any(), any());
    }

    // FE-2d (spec §6.2.4): the one documented exception to ConsumeResult's blanket collapse rule — see
    // EmailVerificationService's own class javadoc for why this is safe for this flow specifically.
    @Test
    void anExpiredCodeReturnsTheDistinctExpiredOutcome() {
        when(oneTimeTokenService.consumeCode(any(), any(), any(), any()))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.EXPIRED));
        assertThat(service.verify(ctx, "a@x.com", "000000")).isEqualTo(VerificationOutcome.EXPIRED);
        verify(userService, never()).markEmailVerified(any(), any());
    }

    @Test
    void anAlreadyUsedCodeReturnsTheDistinctAlreadyUsedOutcomeWithNoReVerificationAttempt() {
        when(oneTimeTokenService.consumeCode(any(), any(), any(), any()))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.ALREADY_USED));
        assertThat(service.verify(ctx, "a@x.com", "000000")).isEqualTo(VerificationOutcome.ALREADY_USED);
        // A failed ConsumeResult carries no userId — there is nothing to act on, so nothing should be attempted.
        verify(userService, never()).markEmailVerified(any(), any());
        verify(userService, never()).activateUser(any(), any());
        verify(userService, never()).getUserById(any(), any());
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
        service.requestVerification(ctx, userId, "a@x.com", "client-1", "ten_acme");
        verify(oneTimeTokenService, never()).issue(any());
        verify(notifier, never()).sendEmailVerificationCode(any(), any(), any());
    }

    @Test
    void deliveryFailureStillReturnsUniformlyAndNeverLogsTheCode() {
        // Enumeration-safety under SMTP outage: an existing-account request whose email send FAILS must behave
        // identically to the non-existent-account case (no exception escapes → the controller returns a uniform 200).
        String code = "424242";
        when(oneTimeTokenService.issue(any()))
                .thenReturn(new RawOneTimeToken(code, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10))));
        doThrow(new NotificationDeliveryException("EMAIL_VERIFICATION", "a@x.com", new RuntimeException("smtp down")))
                .when(notifier).sendEmailVerificationCode(any(), eq(code), any());

        Logger logger = (Logger) LoggerFactory.getLogger(EmailVerificationService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatCode(() -> service.requestVerification(ctx, userId, "a@x.com", "client-1", "ten_acme"))
                    .doesNotThrowAnyException();
        } finally {
            logger.detachAppender(logs);
        }

        // delivery WAS attempted (token still issued) — the built verifyUrl carries the code, so it must never be
        // logged either (asserted below alongside the raw code).
        verify(notifier).sendEmailVerificationCode(eq("a@x.com"), eq(code), any());
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(code)); // but the code is never logged
    }

    @Test
    void requestVerificationBuildsATenantNamespacedVerifyUrlCarryingTheCode() {
        String code = "424242";
        when(oneTimeTokenService.issue(any()))
                .thenReturn(new RawOneTimeToken(code, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10))));

        service.requestVerification(ctx, userId, "a@x.com", "client-1", "ten_acme");

        org.mockito.ArgumentCaptor<String> urlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).sendEmailVerificationCode(eq("a@x.com"), eq(code), urlCaptor.capture());
        String url = urlCaptor.getValue();
        assertThat(url).contains("/t/ten_acme/verify-email");
        assertThat(url).contains("code=" + code);
        assertThat(url).contains("email=a%40x.com");
        assertThat(url).contains("client_id=client-1");
    }

    @Test
    void requestVerificationDegradesToTheUnNamespacedPathWhenTenantSlugIsNull() {
        String code = "424242";
        when(oneTimeTokenService.issue(any()))
                .thenReturn(new RawOneTimeToken(code, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10))));

        service.requestVerification(ctx, userId, "a@x.com", "client-1", null);

        org.mockito.ArgumentCaptor<String> urlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).sendEmailVerificationCode(eq("a@x.com"), eq(code), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).doesNotContain("/t/");
    }

    private UserView user(UserStatus status) {
        return new UserView(userId, tenantId, "a@x.com", false, null, false, "F", "L", status,
                null, Instant.now(), Instant.now(), null, null);
    }
}
