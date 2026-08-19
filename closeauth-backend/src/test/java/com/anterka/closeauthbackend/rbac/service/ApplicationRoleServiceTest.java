package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.exception.ApplicationRoleNotFoundException;
import com.anterka.closeauthbackend.common.exception.ResourceServerNotFoundException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.UserApplicationRole;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleScopeRepository;
import com.anterka.closeauthbackend.rbac.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * FE-4b: {@link ApplicationRoleService#getAssigneeUserIds} — the reverse of {@link
 * ApplicationRoleService#getApplicationRolesForUser}. No prior unit-test coverage existed for this service; this
 * file covers only the new method.
 */
class ApplicationRoleServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID rsId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private ResourceServerRepository resourceServerRepository;
    private ApplicationRoleRepository applicationRoleRepository;
    private ApplicationRoleScopeRepository applicationRoleScopeRepository;
    private UserApplicationRoleRepository userApplicationRoleRepository;
    private ApplicationRoleService service;

    @BeforeEach
    void setUp() {
        applicationRoleRepository = Mockito.mock(ApplicationRoleRepository.class);
        applicationRoleScopeRepository = Mockito.mock(ApplicationRoleScopeRepository.class);
        userApplicationRoleRepository = Mockito.mock(UserApplicationRoleRepository.class);
        resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        ResourceServerScopeRepository resourceServerScopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator = Mockito.mock(CommandValidator.class);
        AuditEmitter auditEmitter = Mockito.mock(AuditEmitter.class);
        service = new ApplicationRoleService(applicationRoleRepository, applicationRoleScopeRepository,
                userApplicationRoleRepository, resourceServerRepository, resourceServerScopeRepository,
                tenantService, commandValidator, auditEmitter);

        when(resourceServerRepository.findByIdInTenant(rsId, tenantId)).thenReturn(Optional.of(resourceServer()));
        when(applicationRoleRepository.findByIdAndResourceServerId(roleId, rsId))
                .thenReturn(Optional.of(applicationRole()));
    }

    @Test
    void getAssigneeUserIdsReturnsEveryHolderOfTheRole() {
        UUID holder1 = UUID.randomUUID();
        UUID holder2 = UUID.randomUUID();
        when(userApplicationRoleRepository.findByApplicationRoleId(roleId))
                .thenReturn(List.of(assignment(holder1), assignment(holder2)));

        List<UUID> result = service.getAssigneeUserIds(ctx, rsId, roleId);

        assertThat(result).containsExactlyInAnyOrder(holder1, holder2);
    }

    @Test
    void getAssigneeUserIdsRefusesARoleFromAnotherResourceServer() {
        // loadRoleInResourceServer scopes by (roleId, rsId) together — a role that exists but under a
        // different RS must 404, not silently answer with the wrong role's holders.
        when(applicationRoleRepository.findByIdAndResourceServerId(roleId, rsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAssigneeUserIds(ctx, rsId, roleId))
                .isInstanceOf(ApplicationRoleNotFoundException.class);
        Mockito.verify(userApplicationRoleRepository, Mockito.never()).findByApplicationRoleId(Mockito.any());
    }

    @Test
    void getAssigneeUserIdsRefusesAResourceServerFromAnotherTenant() {
        when(resourceServerRepository.findByIdInTenant(rsId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAssigneeUserIds(ctx, rsId, roleId))
                .isInstanceOf(ResourceServerNotFoundException.class);
        Mockito.verify(userApplicationRoleRepository, Mockito.never()).findByApplicationRoleId(Mockito.any());
    }

    // ---- countRoleUsageByResourceServer (FE-4b: scope catalog "where used", roles half) --------------------

    @Test
    void countRoleUsageByResourceServerReturnsAMapKeyedByScopeId() {
        UUID scopeA = UUID.randomUUID();
        UUID scopeB = UUID.randomUUID();
        when(applicationRoleScopeRepository.countRoleUsageByResourceServer(rsId)).thenReturn(List.of(
                projection(scopeA, 3L),
                projection(scopeB, 1L)));

        Map<UUID, Long> result = service.countRoleUsageByResourceServer(ctx, rsId);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(scopeA, 3L, scopeB, 1L));
    }

    @Test
    void countRoleUsageByResourceServerIsEmptyWhenNoScopeIsBundledIntoAnyRole() {
        when(applicationRoleScopeRepository.countRoleUsageByResourceServer(rsId)).thenReturn(List.of());

        assertThat(service.countRoleUsageByResourceServer(ctx, rsId)).isEmpty();
    }

    @Test
    void countRoleUsageByResourceServerRefusesAResourceServerFromAnotherTenant() {
        when(resourceServerRepository.findByIdInTenant(rsId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.countRoleUsageByResourceServer(ctx, rsId))
                .isInstanceOf(ResourceServerNotFoundException.class);
    }

    private com.anterka.closeauthbackend.rbac.repository.ScopeRoleCountProjection projection(UUID scopeId, long count) {
        return new com.anterka.closeauthbackend.rbac.repository.ScopeRoleCountProjection() {
            @Override
            public UUID getScopeId() {
                return scopeId;
            }

            @Override
            public long getRoleCount() {
                return count;
            }
        };
    }

    private ResourceServer resourceServer() {
        ResourceServer rs = new ResourceServer();
        rs.setId(rsId);
        rs.setTenantId(tenantId);
        return rs;
    }

    private ApplicationRole applicationRole() {
        ApplicationRole role = new ApplicationRole();
        role.setId(roleId);
        role.setResourceServerId(rsId);
        role.setTenantId(tenantId);
        return role;
    }

    private UserApplicationRole assignment(UUID userId) {
        UserApplicationRole uar = new UserApplicationRole();
        uar.setId(UUID.randomUUID());
        uar.setUserId(userId);
        uar.setResourceServerId(rsId);
        uar.setApplicationRoleId(roleId);
        uar.setTenantId(tenantId);
        return uar;
    }
}
