package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.common.security.TenantContext;
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
import org.mockito.Mockito;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the login <b>policy</b> layered over 3b's policy-free {@code verifyPassword} primitive: tenant-status
 * gating, user-status gating, and — critically — that every refusal is a uniform, enumeration-safe failure while a
 * valid ACTIVE user in an ACTIVE tenant succeeds carrying the real idp.
 */
class LoginPolicyServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private TenantService tenantService;
    private UserService userService;
    private LoginPolicyService service;

    @BeforeEach
    void setUp() {
        tenantService = Mockito.mock(TenantService.class);
        userService = Mockito.mock(UserService.class);
        service = new LoginPolicyService(tenantService, userService);
        when(tenantService.getTenantById(tenantId)).thenReturn(tenant(TenantStatus.ACTIVE));
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

    private TenantView tenant(TenantStatus status) {
        return new TenantView(tenantId, "acme", "Acme", status, Instant.now(), Instant.now(), null);
    }

    private UserView user(UserStatus status) {
        return new UserView(userId, tenantId, "a@x.com", true, null, false, "F", "L", status,
                null, Instant.now(), Instant.now());
    }
}
