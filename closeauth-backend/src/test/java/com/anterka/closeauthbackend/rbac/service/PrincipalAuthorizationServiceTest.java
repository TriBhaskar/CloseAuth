package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleScopeRepository;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserPlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PrincipalAuthorizationServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final String RS_SLUG = "todomaster-api";
    private static final String RS_AUDIENCE = "https://acme.rs.closeauth.io/todomaster-api";

    private UserPlatformRoleRepository userPlatformRoleRepository;
    private PlatformRoleRepository platformRoleRepository;
    private UserTenantRoleRepository userTenantRoleRepository;
    private TenantRoleRepository tenantRoleRepository;
    private UserApplicationRoleRepository userApplicationRoleRepository;
    private ApplicationRoleRepository applicationRoleRepository;
    private ApplicationRoleScopeRepository applicationRoleScopeRepository;
    private ResourceServerRepository resourceServerRepository;
    private ResourceServerScopeRepository resourceServerScopeRepository;
    private PrincipalAuthorizationService service;

    @BeforeEach
    void setUp() {
        userPlatformRoleRepository = Mockito.mock(UserPlatformRoleRepository.class);
        platformRoleRepository = Mockito.mock(PlatformRoleRepository.class);
        userTenantRoleRepository = Mockito.mock(UserTenantRoleRepository.class);
        tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        userApplicationRoleRepository = Mockito.mock(UserApplicationRoleRepository.class);
        applicationRoleRepository = Mockito.mock(ApplicationRoleRepository.class);
        applicationRoleScopeRepository = Mockito.mock(ApplicationRoleScopeRepository.class);
        resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        resourceServerScopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        service = new PrincipalAuthorizationService(userPlatformRoleRepository, platformRoleRepository,
                userTenantRoleRepository, tenantRoleRepository, userApplicationRoleRepository,
                applicationRoleRepository, applicationRoleScopeRepository, resourceServerRepository,
                resourceServerScopeRepository);
    }

    @Test
    void resolvesFullyQualifiedScopesUsingSlugAndAppRolesUsingAudience() {
        UUID userId = UUID.randomUUID();
        UUID rsId = UUID.randomUUID();
        UUID appRoleId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID tenantRoleId = UUID.randomUUID();

        // no platform roles
        when(userPlatformRoleRepository.findByUserId(userId)).thenReturn(List.of());
        when(platformRoleRepository.findAllById(any())).thenReturn(List.of());

        // one tenant role: TENANT_MEMBER
        UserTenantRole utr = new UserTenantRole();
        utr.setTenantRoleId(tenantRoleId);
        when(userTenantRoleRepository.findByUserIdAndTenantId(userId, TENANT_A)).thenReturn(List.of(utr));
        TenantRole tenantRole = new TenantRole();
        tenantRole.setId(tenantRoleId);
        tenantRole.setTenantId(TENANT_A);
        tenantRole.setName("TENANT_MEMBER");
        when(tenantRoleRepository.findAllById(any())).thenReturn(List.of(tenantRole));

        // one application role (EDITOR) in the RS, bundling scope "read"
        UserApplicationRole uar = new UserApplicationRole();
        uar.setResourceServerId(rsId);
        uar.setApplicationRoleId(appRoleId);
        uar.setTenantId(TENANT_A);
        when(userApplicationRoleRepository.findByUserId(userId)).thenReturn(List.of(uar));

        ResourceServer rs = new ResourceServer();
        rs.setId(rsId);
        rs.setSlug(RS_SLUG);
        rs.setAudienceIdentifier(RS_AUDIENCE);
        when(resourceServerRepository.findById(rsId)).thenReturn(Optional.of(rs));

        ApplicationRole appRole = new ApplicationRole();
        appRole.setId(appRoleId);
        appRole.setName("EDITOR");
        when(applicationRoleRepository.findAllById(any())).thenReturn(List.of(appRole));

        ApplicationRoleScope ars = new ApplicationRoleScope();
        ars.setResourceServerScopeId(scopeId);
        when(applicationRoleScopeRepository.findByApplicationRole_Id(appRoleId)).thenReturn(List.of(ars));

        ResourceServerScope scope = new ResourceServerScope();
        scope.setId(scopeId);
        scope.setScopeName("read");
        when(resourceServerScopeRepository.findAllById(any())).thenReturn(List.of(scope));

        ResolvedAuthorization result = service.resolve(TenantContext.of(TENANT_A), userId);

        // scope uses the RS SLUG prefix, never the audience URI
        assertThat(result.scopes()).containsExactly(RS_SLUG + ":read");
        assertThat(result.scopes()).doesNotContain(RS_AUDIENCE + ":read");
        assertThat(result.scopes().get(0)).doesNotContain("https://");

        // app_roles[].rs uses the AUDIENCE URI, not the slug
        assertThat(result.appRoles()).hasSize(1);
        assertThat(result.appRoles().get(0).rs()).isEqualTo(RS_AUDIENCE);
        assertThat(result.appRoles().get(0).roles()).containsExactly("EDITOR");

        assertThat(result.tenantRoles()).containsExactly("TENANT_MEMBER");
        assertThat(result.platformRoles()).isEmpty();
    }
}
