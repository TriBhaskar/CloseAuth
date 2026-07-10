package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.exception.ScopeResourceServerMismatchException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleScopeRepository;
import com.anterka.closeauthbackend.rbac.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationRoleServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID RS_A = UUID.randomUUID();
    private static final UUID RS_B = UUID.randomUUID();
    private final TenantContext ctxA = TenantContext.of(TENANT_A);

    private ApplicationRoleRepository applicationRoleRepository;
    private ApplicationRoleScopeRepository applicationRoleScopeRepository;
    private ResourceServerScopeRepository resourceServerScopeRepository;
    private ApplicationRoleService service;

    @BeforeEach
    void setUp() {
        applicationRoleRepository = Mockito.mock(ApplicationRoleRepository.class);
        applicationRoleScopeRepository = Mockito.mock(ApplicationRoleScopeRepository.class);
        resourceServerScopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        ResourceServerRepository resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        UserApplicationRoleRepository userApplicationRoleRepository = Mockito.mock(UserApplicationRoleRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        service = new ApplicationRoleService(applicationRoleRepository, applicationRoleScopeRepository,
                userApplicationRoleRepository, resourceServerRepository, resourceServerScopeRepository,
                tenantService, commandValidator,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(applicationRoleScopeRepository.save(any(ApplicationRoleScope.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApplicationRole roleForRs(UUID resourceServerId) {
        ApplicationRole role = new ApplicationRole();
        role.setId(UUID.randomUUID());
        role.setResourceServerId(resourceServerId);
        role.setTenantId(TENANT_A);
        role.setName("EDITOR");
        return role;
    }

    private ResourceServerScope scopeOwnedBy(UUID resourceServerId) {
        ResourceServer rs = new ResourceServer();
        rs.setId(resourceServerId);
        ResourceServerScope scope = new ResourceServerScope();
        scope.setId(UUID.randomUUID());
        scope.setResourceServer(rs);
        scope.setScopeName("read");
        return scope;
    }

    @Test
    void addingAScopeFromADifferentResourceServerIsRejected() {
        ApplicationRole role = roleForRs(RS_A);
        ResourceServerScope foreignScope = scopeOwnedBy(RS_B); // belongs to RS-B, not RS-A
        when(applicationRoleRepository.findByIdAndTenantId(role.getId(), TENANT_A)).thenReturn(Optional.of(role));
        when(resourceServerScopeRepository.findById(foreignScope.getId())).thenReturn(Optional.of(foreignScope));

        assertThatThrownBy(() -> service.addScopeToRole(ctxA, role.getId(), foreignScope.getId()))
                .isInstanceOf(ScopeResourceServerMismatchException.class);
        verify(applicationRoleScopeRepository, never()).save(any());
    }

    @Test
    void addingAScopeFromTheSameResourceServerSucceeds() {
        ApplicationRole role = roleForRs(RS_A);
        ResourceServerScope ownScope = scopeOwnedBy(RS_A); // same RS as the role
        when(applicationRoleRepository.findByIdAndTenantId(role.getId(), TENANT_A)).thenReturn(Optional.of(role));
        when(resourceServerScopeRepository.findById(ownScope.getId())).thenReturn(Optional.of(ownScope));
        when(applicationRoleScopeRepository
                .existsByApplicationRole_IdAndResourceServerScopeId(role.getId(), ownScope.getId()))
                .thenReturn(false);

        service.addScopeToRole(ctxA, role.getId(), ownScope.getId());

        verify(applicationRoleScopeRepository).save(any(ApplicationRoleScope.class));
    }
}
