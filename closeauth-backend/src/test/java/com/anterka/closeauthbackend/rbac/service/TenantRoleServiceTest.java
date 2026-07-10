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

    // ---- last-admin invariant ---------------------------------------------

    @Test
    void revokingLastTenantAdminIsBlocked() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID userId = UUID.randomUUID();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, admin.getId()))
                .thenReturn(Optional.of(new UserTenantRole()));
        when(userTenantRoleRepository.countByTenantIdAndTenantRoleId(TENANT_A, admin.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.revokeTenantRole(ctxA, userId, admin.getId()))
                .isInstanceOf(LastTenantAdminException.class);
        verify(userTenantRoleRepository, never()).delete(any());
    }

    @Test
    void revokingTenantAdminSucceedsWhenAnotherRemains() {
        TenantRole admin = role(SystemRoleNames.TENANT_ADMIN, true);
        UUID userId = UUID.randomUUID();
        UserTenantRole assignment = new UserTenantRole();
        when(tenantRoleRepository.findByIdAndTenantId(admin.getId(), TENANT_A)).thenReturn(Optional.of(admin));
        when(userTenantRoleRepository.findByUserIdAndTenantIdAndTenantRoleId(userId, TENANT_A, admin.getId()))
                .thenReturn(Optional.of(assignment));
        when(userTenantRoleRepository.countByTenantIdAndTenantRoleId(TENANT_A, admin.getId())).thenReturn(2L);

        service.revokeTenantRole(ctxA, userId, admin.getId());

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
        verify(userTenantRoleRepository, never()).countByTenantIdAndTenantRoleId(any(), any());
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
