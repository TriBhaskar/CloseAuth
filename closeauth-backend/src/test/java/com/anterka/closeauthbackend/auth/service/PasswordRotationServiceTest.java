package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationOutcome;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationResult;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the forced-password-rotation flow (Phase 2). Mirrors {@link PasswordResetServiceTest}'s style —
 * the two services are structural siblings — but focuses on what's specific to this scenario: the invalidate-before-
 * issue ordering (§2.3), that {@code userId} comes only from the consumed token (never trusted from elsewhere), the
 * stale-token guard, and that revoke happens before the caller can establish a session.
 */
class PasswordRotationServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private OneTimeTokenService oneTimeTokenService;
    private UserService userService;
    private AuthServerSessionService sessionService;
    private AuditEmitter auditEmitter;
    private PasswordRotationService service;

    @BeforeEach
    void setUp() {
        oneTimeTokenService = Mockito.mock(OneTimeTokenService.class);
        userService = Mockito.mock(UserService.class);
        sessionService = Mockito.mock(AuthServerSessionService.class);
        auditEmitter = Mockito.mock(AuditEmitter.class);
        service = new PasswordRotationService(oneTimeTokenService, userService, sessionService,
                new CloseAuthProperties(), auditEmitter);
    }

    @Test
    void beginRotationInvalidatesBeforeIssuingAndUsesTheOnboardingTtl() {
        when(oneTimeTokenService.issue(any()))
                .thenReturn(new RawOneTimeToken("raw-secret", UUID.randomUUID(), Instant.now()));

        String url = service.beginRotation(ctx, userId, "Admin@X.com", "client-1", "scope=openid&state=abc");

        InOrder order = inOrder(oneTimeTokenService);
        order.verify(oneTimeTokenService)
                .invalidateForTarget(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING, tenantId, "admin@x.com");
        order.verify(oneTimeTokenService).issue(any());

        var captor = org.mockito.ArgumentCaptor.forClass(IssueTokenCommand.class);
        verify(oneTimeTokenService).issue(captor.capture());
        assertThat(captor.getValue().purpose()).isEqualTo(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING);
        assertThat(captor.getValue().ttl()).isEqualTo(new CloseAuthProperties().getOneTimeToken().getTenantAdminOnboardingTtl());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().target()).isEqualTo("admin@x.com");

        assertThat(url)
                .startsWith("http://localhost:8080/password-rotation?token=raw-secret")
                .contains("client_id=client-1")
                .contains("authorize_query=");
    }

    @Test
    void beginRotationOmitsClientIdAndAuthorizeQueryWhenAbsent() {
        when(oneTimeTokenService.issue(any()))
                .thenReturn(new RawOneTimeToken("raw-secret", UUID.randomUUID(), Instant.now()));

        String url = service.beginRotation(ctx, userId, "a@x.com", null, null);

        assertThat(url).isEqualTo("http://localhost:8080/password-rotation?token=raw-secret");
    }

    @Test
    void completeRotationOnFailedConsumeWritesNoPasswordAndRevokesNothing() {
        when(oneTimeTokenService.consume(eq("bad"), eq(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING), eq(tenantId)))
                .thenReturn(ConsumeResult.failure(ConsumeResult.FailureReason.NOT_FOUND));

        RotationResult result = service.completeRotation(ctx, "bad", "new-password-123");

        assertThat(result.outcome()).isEqualTo(RotationOutcome.INVALID);
        assertThat(result.userId()).isNull();
        verify(userService, never()).completeForcedRotation(any(), any(), any());
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
        verify(auditEmitter, never()).emit(any());
    }

    @Test
    void completeRotationRejectsAStaleTokenWhoseRotationIsNoLongerPending() {
        // The token was validly consumed, but the target user's must_change_password is already false — e.g. a
        // race with a concurrent completion, or the flag was cleared some other way. Must not silently re-apply.
        when(oneTimeTokenService.consume(eq("tok"), eq(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING), eq(tenantId)))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING,
                        tenantId, userId, "a@x.com", null));
        when(userService.getLocalCredentialState(ctx, userId))
                .thenReturn(Optional.of(new LocalCredentialState(false, null)));

        RotationResult result = service.completeRotation(ctx, "tok", "new-password-123");

        assertThat(result.outcome()).isEqualTo(RotationOutcome.INVALID);
        verify(userService, never()).completeForcedRotation(any(), any(), any());
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
    }

    @Test
    void completeRotationSucceedsAndUserIdComesOnlyFromTheToken() {
        UUID tokenOwnerUserId = userId; // the only source of truth — nothing else in the request carries a user id
        when(oneTimeTokenService.consume(eq("tok"), eq(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING), eq(tenantId)))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING,
                        tenantId, tokenOwnerUserId, "a@x.com", null));
        when(userService.getLocalCredentialState(ctx, tokenOwnerUserId))
                .thenReturn(Optional.of(new LocalCredentialState(true, null)));
        when(sessionService.revokeAllUserSessions(tenantId, tokenOwnerUserId)).thenReturn(2);

        RotationResult result = service.completeRotation(ctx, "tok", "new-password-123");

        assertThat(result.outcome()).isEqualTo(RotationOutcome.ROTATED);
        assertThat(result.userId()).isEqualTo(tokenOwnerUserId);
        verify(userService).completeForcedRotation(ctx, tokenOwnerUserId, "new-password-123");
        verify(oneTimeTokenService).invalidateForTarget(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING, tenantId, "a@x.com");
    }

    @Test
    void completeRotationRevokesSessionsBeforeTheCallerCanEstablishANewOne() {
        // Ordering is load-bearing: completeRotation must finish revoking BEFORE returning, so the controller's
        // subsequent establishSessionAndResolveRedirect call is never undone by a revoke that runs after it.
        when(oneTimeTokenService.consume(eq("tok"), eq(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING), eq(tenantId)))
                .thenReturn(ConsumeResult.success(UUID.randomUUID(), OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING,
                        tenantId, userId, "a@x.com", null));
        when(userService.getLocalCredentialState(ctx, userId))
                .thenReturn(Optional.of(new LocalCredentialState(true, null)));

        service.completeRotation(ctx, "tok", "new-password-123");

        InOrder order = inOrder(userService, sessionService);
        order.verify(userService).completeForcedRotation(ctx, userId, "new-password-123");
        order.verify(sessionService).revokeAllUserSessions(tenantId, userId);
    }
}
