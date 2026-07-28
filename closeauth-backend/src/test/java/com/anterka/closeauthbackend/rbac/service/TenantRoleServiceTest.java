package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.exception.LastTenantAdminException;
import com.anterka.closeauthbackend.common.exception.SystemRoleModificationException;
import com.anterka.closeauthbackend.common.exception.TenantRoleConflictException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.dto.CreateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.dto.UpdateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantRoleServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private final TenantContext ctxA = TenantContext.of(TENANT_A);

    private TenantRoleRepository tenantRoleRepository;
    private UserTenantRoleRepository userTenantRoleRepository;
    private TenantRoleService service;

    @BeforeEach
    void setUp() {
        tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        userTenantRoleRepository = Mockito.mock(UserTenantRoleRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        service = new TenantRoleService(tenantRoleRepository, userTenantRoleRepository, tenantService, commandValidator,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(tenantRoleRepository.save(any(TenantRole.class))).thenAnswer(inv -> {
            TenantRole r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private TenantRole role(String name, boolean isSystem) {
        TenantRole r = new TenantRole();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT_A);
        r.setName(name);
        r.setSystem(isSystem);
        return r;
    }

    // ---- last-admin invariant (ACTIVE-only counting) ----------------------

    @Test
    void revokingLastActiveTenantAdminIsBlocked() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID userId = UUID.randomUUID();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, admin.getId()))
                .thenReturn(Optional.of(new UserTenantRole()));
        stubLastActiveAdmin(admin, userId, true, 1L); // target is the sole ACTIVE admin

        assertThatThrownBy(() -> service.revokeTenantRole(ctxA, userId, admin.getId()))
                .isInstanceOf(LastTenantAdminException.class);
        verify(userTenantRoleRepository, never()).delete(any());
    }

    @Test
    void revokingTenantAdminSucceedsWhenAnotherActiveAdminRemains() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID userId = UUID.randomUUID();
        UserTenantRole assignment = new UserTenantRole();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, admin.getId()))
                .thenReturn(Optional.of(assignment));
        stubLastActiveAdmin(admin, userId, true, 2L); // two active admins

        service.revokeTenantRole(ctxA, userId, admin.getId());

        verify(userTenantRoleRepository).delete(assignment);
    }

    @Test
    void revokingASuspendedHoldersDormantRoleIsAllowed() {
        // Regression: a SUSPENDED holder's dormant TENANT_ADMIN row must be revocable — it isn't an ACTIVE admin, so
        // removing it can't orphan the tenant (an active admin still exists). The old all-status count wrongly blocked
        // (or wrongly allowed removing the last active) depending on the sequence; active-only counting fixes both.
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID suspendedHolder = UUID.randomUUID();
        UserTenantRole assignment = new UserTenantRole();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(suspendedHolder, TENANT_A, admin.getId()))
                .thenReturn(Optional.of(assignment));
        stubLastActiveAdmin(admin, suspendedHolder, false, 1L); // target NOT an active holder; 1 active admin exists

        service.revokeTenantRole(ctxA, suspendedHolder, admin.getId());

        verify(userTenantRoleRepository).delete(assignment);
    }

    @Test
    void revokeIsIdempotentWhenUserDoesNotHoldTheRole() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID userId = UUID.randomUUID();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, admin.getId()))
                .thenReturn(Optional.empty());

        service.revokeTenantRole(ctxA, userId, admin.getId());

        verify(userTenantRoleRepository, never()).delete(any());
        verify(userTenantRoleRepository, never()).countActiveHoldersByTenantAndRole(any(), any());
    }

    // ---- isLastTenantAdmin: active-only semantics (shared by suspend + revoke paths) ----

    @Test
    void isLastTenantAdmin_soleActiveAdminIsLast_evenWhenASuspendedHolderRowLingers() {
        // THE sequential-suspension gap: one ACTIVE holder (the target) while a suspended holder's row still exists →
        // active count is 1 → the target IS the last (active) admin → the guard must fire. Under the old all-status
        // count this returned false (2 rows) and the last active admin could be suspended, orphaning the tenant.
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID lastActive = UUID.randomUUID();
        stubLastActiveAdmin(admin, lastActive, true, 1L);
        assertThat(service.isLastTenantAdmin(ctxA, lastActive)).isTrue();
    }

    @Test
    void isLastTenantAdmin_falseForASuspendedHolder() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID suspended = UUID.randomUUID();
        stubLastActiveAdmin(admin, suspended, false, 1L); // not an active holder → not "the last admin"
        assertThat(service.isLastTenantAdmin(ctxA, suspended)).isFalse();
    }

    @Test
    void isLastTenantAdmin_falseWhenTwoActiveAdminsExist() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID one = UUID.randomUUID();
        stubLastActiveAdmin(admin, one, true, 2L);
        assertThat(service.isLastTenantAdmin(ctxA, one)).isFalse();
    }

    /** Stubs the active-only last-admin lookups: the TENANT_ADMIN role resolves; the target's active-holder + count. */
    private void stubLastActiveAdmin(TenantRole adminRole, UUID userId, boolean targetActive, long activeCount) {
        when(tenantRoleRepository.findByTenantIdAndName(TENANT_A, SystemRoleNames.TENANT_ADMIN))
                .thenReturn(Optional.of(adminRole));
        when(userTenantRoleRepository.isActiveHolder(userId, TENANT_A, adminRole.getId())).thenReturn(targetActive);
        when(userTenantRoleRepository.countActiveHoldersByTenantAndRole(TENANT_A, adminRole.getId()))
                .thenReturn(activeCount);
    }

    // ---- CRUD / uniqueness / system-role protection -----------------------

    @Test
    void createRejectsDuplicateName() {
        when(tenantRoleRepository.findByTenantIdAndName(TENANT_A, "Editor"))
                .thenReturn(Optional.of(role("Editor", false)));

        assertThatThrownBy(() -> service.createTenantRole(ctxA, new CreateTenantRoleCommand("Editor", null, false)))
                .isInstanceOf(TenantRoleConflictException.class);
        verify(tenantRoleRepository, never()).save(any());
    }

    @Test
    void createCustomRoleIsNotSystem() {
        when(tenantRoleRepository.findByTenantIdAndName(TENANT_A, "Editor")).thenReturn(Optional.empty());

        var view = service.createTenantRole(ctxA, new CreateTenantRoleCommand("Editor", "desc", true));

        assertThat(view.isSystem()).isFalse();
        assertThat(view.isDefault()).isTrue();
        assertThat(view.name()).isEqualTo("Editor");
    }

    @Test
    void updateOnSystemRoleIsRejected() {
        TenantRole system = role(SystemRoleNames.TENANT_MEMBER, true);
        when(tenantRoleRepository.findByIdAndTenantId(system.getId(), TENANT_A)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service.updateTenantRole(ctxA, system.getId(),
                new UpdateTenantRoleCommand("x", false)))
                .isInstanceOf(SystemRoleModificationException.class);
    }

    @Test
    void deleteOnSystemRoleIsRejected() {
        TenantRole system = role(SystemRoleNames.TENANT_ADMIN, true);
        when(tenantRoleRepository.findByIdAndTenantId(system.getId(), TENANT_A)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service.deleteTenantRole(ctxA, system.getId()))
                .isInstanceOf(SystemRoleModificationException.class);
        verify(tenantRoleRepository, never()).delete(any());
    }

    @Test
    void assignIsIdempotent() {
        TenantRole member = role(SystemRoleNames.TENANT_MEMBER, true);
        UUID userId = UUID.randomUUID();
        when(tenantRoleRepository.findByIdAndTenantId(member.getId(), TENANT_A)).thenReturn(Optional.of(member));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, member.getId()))
                .thenReturn(Optional.of(new UserTenantRole()));

        service.assignTenantRole(ctxA, userId, member.getId(), null);

        verify(userTenantRoleRepository, never()).save(any());
    }
}
