package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.auth.service.TenantOnboardingService;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.session.dto.MeSessionView;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-4a: unit tests for {@link TenantUserController}'s new additions — plain hand-wired Mockito mocks, same style
 * as {@link com.anterka.closeauthbackend.identity.service.UserDeactivationWiringTest}, no MockMvc/Spring context.
 * Covers exactly the logic this session added: bulk role decoration on {@code list}, {@code isLastActiveAdmin}
 * decoration on {@code get}, and — the actual security-relevant piece — that
 * {@code revokeSession}/{@code sessions} never reach across tenant or user boundaries.
 */
class TenantUserControllerTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private UserService userService;
    private TenantRoleService tenantRoleService;
    private AuthServerSessionService sessionService;
    private TenantOnboardingService tenantOnboardingService;
    private TenantUserController controller;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        tenantRoleService = Mockito.mock(TenantRoleService.class);
        sessionService = Mockito.mock(AuthServerSessionService.class);
        tenantOnboardingService = Mockito.mock(TenantOnboardingService.class);
        controller = new TenantUserController(userService, tenantRoleService, sessionService, tenantOnboardingService);
    }

    // ---- list: bulk role decoration --------------------------------------------

    @Test
    void listDecoratesEachUserWithItsHeldRolesFromTheBulkMap() {
        UserView bare = user(userId);
        when(userService.listUsers(eq(ctx), any(), any(), any())).thenReturn(List.of(bare));
        when(tenantRoleService.getTenantRoleNamesByUser(ctx))
                .thenReturn(Map.of(userId, List.of("TENANT_ADMIN", "BILLING_ADMIN")));

        var page = controller.list(tenantId.toString(), 0, 20, null, null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).roles()).containsExactly("TENANT_ADMIN", "BILLING_ADMIN");
    }

    @Test
    void listGivesAUserWithNoHeldRolesAnEmptyListNotNull() {
        when(userService.listUsers(eq(ctx), any(), any(), any())).thenReturn(List.of(user(userId)));
        when(tenantRoleService.getTenantRoleNamesByUser(ctx)).thenReturn(Map.of()); // no one holds anything

        var page = controller.list(tenantId.toString(), 0, 20, null, null, null);

        assertThat(page.items().get(0).roles()).isEmpty();
    }

    // ---- get: isLastActiveAdmin decoration -------------------------------------

    @Test
    void getDecoratesIsLastActiveAdminFromTheService() {
        when(userService.getUserById(ctx, userId)).thenReturn(user(userId));
        when(tenantRoleService.isLastTenantAdmin(ctx, userId)).thenReturn(true);
        when(tenantRoleService.getTenantRolesForUser(ctx, userId)).thenReturn(List.of("TENANT_ADMIN"));

        UserView result = controller.get(tenantId.toString(), userId);

        assertThat(result.isLastActiveAdmin()).isTrue();
        assertThat(result.roles()).containsExactly("TENANT_ADMIN");
    }

    // ---- sessions: tenant/user-scoping (the security-relevant piece) ----------

    @Test
    void revokeSessionSucceedsWhenTheSessionBelongsToTheNamedUserAndTenant() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getById(sessionId)).thenReturn(Optional.of(sessionView(sessionId, userId, tenantId, "key-1")));

        controller.revokeSession(tenantId.toString(), userId, sessionId);

        verify(sessionService).revokeSession("key-1");
    }

    @Test
    void revokeSessionRefusesASessionBelongingToADifferentUserInTheSameTenant() {
        UUID sessionId = UUID.randomUUID();
        // The session is real and IS in this tenant, but belongs to someone else — the path's userId must match too.
        when(sessionService.getById(sessionId))
                .thenReturn(Optional.of(sessionView(sessionId, otherUserId, tenantId, "key-1")));

        assertThatThrownBy(() -> controller.revokeSession(tenantId.toString(), userId, sessionId))
                .isInstanceOf(CloseAuthDomainException.class)
                .hasFieldOrPropertyWithValue("code", "session.not_found");

        verify(sessionService, never()).revokeSession(any());
    }

    @Test
    void revokeSessionRefusesASessionBelongingToTheRightUserInADifferentTenant() {
        UUID sessionId = UUID.randomUUID();
        // Cross-tenant leak attempt: right userId, wrong tenant on the path — must still be refused.
        when(sessionService.getById(sessionId))
                .thenReturn(Optional.of(sessionView(sessionId, userId, otherTenantId, "key-1")));

        assertThatThrownBy(() -> controller.revokeSession(tenantId.toString(), userId, sessionId))
                .isInstanceOf(CloseAuthDomainException.class)
                .hasFieldOrPropertyWithValue("code", "session.not_found");

        verify(sessionService, never()).revokeSession(any());
    }

    @Test
    void revokeSessionRefusesAnUnknownSessionId() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.revokeSession(tenantId.toString(), userId, sessionId))
                .isInstanceOf(CloseAuthDomainException.class)
                .hasFieldOrPropertyWithValue("code", "session.not_found");
    }

    @Test
    void sessionsListsOnlyThisUsersSessionsInThisTenant() {
        when(sessionService.listSessionsForUser(tenantId, userId))
                .thenReturn(List.of(sessionView(UUID.randomUUID(), userId, tenantId, "key-1")));

        List<MeSessionView> result = controller.sessions(tenantId.toString(), userId);

        assertThat(result).hasSize(1);
        verify(sessionService).listSessionsForUser(tenantId, userId);
    }

    @Test
    void revokeAllSessionsDelegatesToTheTenantAndUserScopedServiceCall() {
        controller.revokeAllSessions(tenantId.toString(), userId);

        verify(sessionService).revokeAllUserSessions(tenantId, userId);
    }

    // ---- fixtures ----------------------------------------------------------------

    private UserView user(UUID id) {
        return new UserView(id, tenantId, "a@x.com", true, null, false, "First", "Last", UserStatus.ACTIVE,
                null, Instant.now(), Instant.now(), null, null);
    }

    private SessionView sessionView(UUID id, UUID forUserId, UUID forTenantId, String sessionKey) {
        return new SessionView(id, sessionKey, forUserId, forTenantId, false, "127.0.0.1", "test-agent", null,
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(86400), Instant.now(), null);
    }
}
