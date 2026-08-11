package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.IdpType;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the login <b>policy</b> layered over 3b's policy-free {@code verifyPassword} primitive: tenant-status
 * gating, user-status gating, per-account rate limiting, and — critically — that every refusal (including a
 * throttled attempt) is a uniform, enumeration-safe failure while a valid ACTIVE user in an ACTIVE tenant succeeds
 * carrying the real idp.
 */
class LoginPolicyServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private TenantService tenantService;
    private UserService userService;
    private AuditEmitter auditEmitter;
    private RedisRateLimiter rateLimiter;
    private CloseAuthProperties properties;
    private LoginPolicyService service;

    @BeforeEach
    void setUp() {
        tenantService = Mockito.mock(TenantService.class);
        userService = Mockito.mock(UserService.class);
        auditEmitter = Mockito.mock(AuditEmitter.class);
        rateLimiter = Mockito.mock(RedisRateLimiter.class);
        properties = new CloseAuthProperties(); // real defaults: maxLoginAttempts=5, lockoutDurationMinutes=30
        service = new LoginPolicyService(tenantService, userService, auditEmitter, rateLimiter, properties);
        when(tenantService.getTenantById(tenantId)).thenReturn(tenant(TenantStatus.ACTIVE));
        // Permissive by default — individual tests override to exercise the limiter itself.
        when(rateLimiter.isLimited(any(), anyInt())).thenReturn(false);
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        // No pending rotation / no temp credential by default — the common case (a user-chosen password, or no
        // local password at all). Individual tests override to exercise the credential-lifecycle gate itself.
        when(userService.getLocalCredentialState(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void activeUserInActiveTenantSucceedsWithIdp() {
        when(userService.verifyPassword(eq(ctx), eq("a@x.com"), eq("pw")))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.ACTIVE)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.userId()).isEqualTo(userId);
        assertThat(outcome.idp()).isEqualTo(IdpType.LOCAL_PASSWORD);
    }

    @Test
    void suspendedTenantRefusesBeforeCheckingCredentials() {
        when(tenantService.getTenantById(tenantId)).thenReturn(tenant(TenantStatus.SUSPENDED));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.TENANT_NOT_ACTIVE);
        verify(userService, never()).verifyPassword(any(), any(), any()); // no credential work for a dead tenant
    }

    @Test
    void badCredentialsRefused() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.failure(PasswordVerificationResult.FailureReason.BAD_PASSWORD));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "wrong");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.INVALID_CREDENTIALS);
    }

    @Test
    void pendingUserRefused() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.PENDING)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.USER_NOT_ACTIVE);
    }

    @Test
    void suspendedUserRefused() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.SUSPENDED)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.USER_NOT_ACTIVE);
    }

    @Test
    void rateLimitedRefusesBeforeAnyTenantOrCredentialWork() {
        when(rateLimiter.isLimited(any(), anyInt())).thenReturn(true);

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.RATE_LIMITED);
        verify(tenantService, never()).getTenantById(any());
        verify(userService, never()).verifyPassword(any(), any(), any());
    }

    @Test
    void limiterIsKeyedOnTenantAndNormalizedEmail() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.failure(PasswordVerificationResult.FailureReason.BAD_PASSWORD));

        service.authenticate(ctx, "  Alice@X.COM  ", "pw");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).isLimited(key.capture(), anyInt());
        assertThat(key.getValue()).isEqualTo("rl:login:password:" + tenantId + ":alice@x.com");
    }

    @Test
    void limiterUsesConfiguredAttemptsAndWindow() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.failure(PasswordVerificationResult.FailureReason.BAD_PASSWORD));

        service.authenticate(ctx, "a@x.com", "wrong");

        verify(rateLimiter).isLimited(any(), eq(5));
        verify(rateLimiter).tryAcquire(any(), eq(5), eq(Duration.ofMinutes(30)));
    }

    @Test
    void successfulLoginDoesNotConsumeAttemptBudget() {
        when(userService.verifyPassword(eq(ctx), eq("a@x.com"), eq("pw")))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.ACTIVE)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isTrue();
        verify(rateLimiter, never()).tryAcquire(any(), anyInt(), any()); // gate consulted (isLimited), never recorded
    }

    @Test
    void badCredentialsRecordsAnAttempt() {
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.failure(PasswordVerificationResult.FailureReason.BAD_PASSWORD));

        service.authenticate(ctx, "a@x.com", "wrong");

        verify(rateLimiter).tryAcquire(any(), anyInt(), any());
    }

    @Test
    void rateLimitedDoesNotDoubleRecordAgainstTheBucketItJustFoundExhausted() {
        when(rateLimiter.isLimited(any(), anyInt())).thenReturn(true);

        service.authenticate(ctx, "a@x.com", "pw");

        verify(rateLimiter, never()).tryAcquire(any(), anyInt(), any());
    }

    @Test
    void rateLimitedEmitsLoginFailureAudit() {
        when(rateLimiter.isLimited(any(), anyInt())).thenReturn(true);

        service.authenticate(ctx, "a@x.com", "pw");

        ArgumentCaptor<com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent> captor =
                ArgumentCaptor.forClass(com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent.class);
        verify(auditEmitter).emit(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo(com.anterka.closeauthbackend.audit.enums.AuditEventType.USER_LOGIN_FAILURE);
        assertThat(captor.getValue().getErrorCode()).isEqualTo(LoginOutcome.FailureReason.RATE_LIMITED.name());
    }

    // ---- Phase 2 (forced credential rotation): the shared credential-lifecycle gate ---------------------------

    @Test
    void wrongPasswordOnAPendingRotationAccountIsInvalidCredentialsNeverRotationRequired() {
        // Proves the gate is post-verification and unprobeable: even though the account IS pending rotation, a
        // WRONG password must never reveal that by returning rotationRequired — the caller learns nothing without
        // the correct password.
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.failure(PasswordVerificationResult.FailureReason.BAD_PASSWORD));
        when(userService.getLocalCredentialState(eq(ctx), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, null)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "wrong");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.rotationRequired()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.INVALID_CREDENTIALS);
        verify(userService, never()).getLocalCredentialState(any(), any());
    }

    @Test
    void exhaustedBudgetRefusesBeforeConsultingCredentialLifecycle() {
        when(rateLimiter.isLimited(any(), anyInt())).thenReturn(true);

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.RATE_LIMITED);
        verify(userService, never()).getLocalCredentialState(any(), any());
    }

    @Test
    void inactiveUserWithPendingRotationIsUserNotActive() {
        // User-status gate still wins over the credential-lifecycle gate — an inactive user never sees rotation.
        when(userService.verifyPassword(any(), any(), any()))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.SUSPENDED)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.USER_NOT_ACTIVE);
        verify(userService, never()).getLocalCredentialState(any(), any());
    }

    @Test
    void expiredTempCredentialIsTheUniformFailureAndRecordsAnAttempt() {
        when(userService.verifyPassword(eq(ctx), eq("a@x.com"), eq("pw")))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.ACTIVE)));
        when(userService.getLocalCredentialState(eq(ctx), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, Instant.now().minusSeconds(60))));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.rotationRequired()).isFalse();
        assertThat(outcome.reason()).isEqualTo(LoginOutcome.FailureReason.TEMP_CREDENTIAL_EXPIRED);
        // Consumes budget exactly like a bad password — an attacker must not be able to distinguish "expired" from
        // "wrong" by watching whether the attempt counter advances.
        verify(rateLimiter).tryAcquire(any(), anyInt(), any());
    }

    @Test
    void rotationRequiredDoesNotConsumeBudgetOrEmitLoginSuccess() {
        when(userService.verifyPassword(eq(ctx), eq("a@x.com"), eq("pw")))
                .thenReturn(PasswordVerificationResult.success(user(UserStatus.ACTIVE)));
        when(userService.getLocalCredentialState(eq(ctx), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, null)));

        LoginOutcome outcome = service.authenticate(ctx, "a@x.com", "pw");

        assertThat(outcome.rotationRequired()).isTrue();
        assertThat(outcome.userId()).isEqualTo(userId);
        assertThat(outcome.success()).isFalse();
        // A proven credential leaks nothing and must not cost the legitimate holder any of their own budget.
        verify(rateLimiter, never()).tryAcquire(any(), anyInt(), any());
        verify(auditEmitter, never()).emit(any());
    }

    @Test
    void isLoginAllowedFalseWhenRotationIsPending() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(user(UserStatus.ACTIVE));
        when(userService.getLocalCredentialState(eq(TenantContext.of(tenantId)), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, null)));

        assertThat(service.isLoginAllowed(tenantId, userId)).isFalse();
    }

    @Test
    void isLoginAllowedFalseWhenTempCredentialExpired() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(user(UserStatus.ACTIVE));
        when(userService.getLocalCredentialState(eq(TenantContext.of(tenantId)), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, Instant.now().minusSeconds(60))));

        assertThat(service.isLoginAllowed(tenantId, userId)).isFalse();
    }

    @Test
    void isLoginAllowedTrueForAnOrdinaryActiveUser() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(user(UserStatus.ACTIVE));

        assertThat(service.isLoginAllowed(tenantId, userId)).isTrue();
    }

    private TenantView tenant(TenantStatus status) {
        return new TenantView(tenantId, "acme", "Acme", status, Instant.now(), Instant.now(), null, null);
    }

    private UserView user(UserStatus status) {
        return new UserView(userId, tenantId, "a@x.com", true, null, false, "F", "L", status,
                null, Instant.now(), Instant.now());
    }
}
