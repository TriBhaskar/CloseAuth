package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FE-4b: {@link TenantRoleService#getAssigneeUserIds} — the reverse of {@link
 * TenantRoleService#getTenantRolesForUser}. No prior unit-test coverage existed for this service (it's exercised
 * via the gated integration suite); this file covers only the new method, not a full retrofit of the class.
 */
class TenantRoleServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID tenantRoleId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private UserTenantRoleRepository userTenantRoleRepository;
    private TenantRoleService service;

    @BeforeEach
    void setUp() {
        TenantRoleRepository tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        userTenantRoleRepository = Mockito.mock(UserTenantRoleRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator = Mockito.mock(CommandValidator.class);
        AuditEmitter auditEmitter = Mockito.mock(AuditEmitter.class);
        service = new TenantRoleService(tenantRoleRepository, userTenantRoleRepository, tenantService,
                commandValidator, auditEmitter);
    }

    @Test
    void getAssigneeUserIdsReturnsEveryHolderOfTheRoleInThisTenant() {
        UUID holder1 = UUID.randomUUID();
        UUID holder2 = UUID.randomUUID();
        when(userTenantRoleRepository.findByTenantIdAndTenantRoleId(tenantId, tenantRoleId))
                .thenReturn(List.of(assignment(holder1), assignment(holder2)));

        List<UUID> result = service.getAssigneeUserIds(ctx, tenantRoleId);

        assertThat(result).containsExactlyInAnyOrder(holder1, holder2);
    }

    @Test
    void getAssigneeUserIdsReturnsEmptyForAnUnassignedRole() {
        when(userTenantRoleRepository.findByTenantIdAndTenantRoleId(tenantId, tenantRoleId)).thenReturn(List.of());

        assertThat(service.getAssigneeUserIds(ctx, tenantRoleId)).isEmpty();
    }

    @Test
    void getAssigneeUserIdsReusesTheSameQueryTheLastAdminInvariantUses() {
        // Not a new query — the exact repository method isLastTenantAdmin already calls, so the two reads can
        // never observe different data.
        service.getAssigneeUserIds(ctx, tenantRoleId);

        Mockito.verify(userTenantRoleRepository).findByTenantIdAndTenantRoleId(tenantId, tenantRoleId);
    }

    private UserTenantRole assignment(UUID userId) {
        UserTenantRole utr = new UserTenantRole();
        utr.setId(UUID.randomUUID());
        utr.setUserId(userId);
        utr.setTenantId(tenantId);
        utr.setTenantRoleId(tenantRoleId);
        return utr;
    }
}
