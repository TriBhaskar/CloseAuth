package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.exception.LastTenantAdminException;
import com.anterka.closeauthbackend.common.security.PasswordHasher;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.repository.UserRepository;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 7b security wiring on user deactivation (§7.8): suspending/deleting a user (1) is refused if they are the last
 * {@code TENANT_ADMIN} (last-admin invariant, 3c-ii → 409), (2) writes the token-revocation marker (4b-ii) so their
 * live tokens die — not merely at expiry, and (3, FE-4a) revokes their Auth Server SSO sessions too, so an existing
 * browser session doesn't keep working on idle-timeout alone. Parallel to 7a's platform-admin proof.
 */
class UserDeactivationWiringTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private UserRepository userRepository;
    private TenantRoleService tenantRoleService;
    private TokenRevocationService tokenRevocationService;
    private AuthServerSessionService sessionService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        tenantRoleService = Mockito.mock(TenantRoleService.class);
        tokenRevocationService = Mockito.mock(TokenRevocationService.class);
        sessionService = Mockito.mock(AuthServerSessionService.class);
        PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(4)));
        CommandValidator validator = new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        userService = new UserService(userRepository, tenantService, new PasswordHasher(encoder), validator,
                new UserStateMachine(), List.of(), tenantRoleService, tokenRevocationService, sessionService,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));

        User active = new User();
        active.setId(userId);
        active.setTenantId(tenantId);
        active.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByIdInTenant(userId, tenantId)).thenReturn(Optional.of(active));
    }

    @Test
    void suspendingANonLastAdminRevokesTheirTokensAndSessions() {
        when(tenantRoleService.isLastTenantAdmin(ctx, userId)).thenReturn(false);

        userService.suspendUser(ctx, userId);

        verify(tokenRevocationService).revokeAllUserTokens(tenantId, userId); // live tokens die now
        verify(sessionService).revokeAllUserSessions(tenantId, userId); // SSO sessions die now too
    }

    @Test
    void deletingANonLastAdminRevokesTheirTokensAndSessions() {
        when(tenantRoleService.isLastTenantAdmin(ctx, userId)).thenReturn(false);

        userService.deleteUser(ctx, userId);

        verify(tokenRevocationService).revokeAllUserTokens(tenantId, userId);
        verify(sessionService).revokeAllUserSessions(tenantId, userId);
    }

    @Test
    void suspendingTheLastTenantAdminIsRefusedAndRevokesNothing() {
        when(tenantRoleService.isLastTenantAdmin(ctx, userId)).thenReturn(true);

        assertThatThrownBy(() -> userService.suspendUser(ctx, userId))
                .isInstanceOf(LastTenantAdminException.class);

        verify(tokenRevocationService, never()).revokeAllUserTokens(any(), any()); // refused before any mutation
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
    }

    @Test
    void activatingAUserNeverRevokesTokensOrSessions() {
        User suspended = new User();
        suspended.setId(userId);
        suspended.setTenantId(tenantId);
        suspended.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByIdInTenant(userId, tenantId)).thenReturn(Optional.of(suspended));

        userService.activateUser(ctx, userId);

        verify(tokenRevocationService, never()).revokeAllUserTokens(any(), any());
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
        assertThat(suspended.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
