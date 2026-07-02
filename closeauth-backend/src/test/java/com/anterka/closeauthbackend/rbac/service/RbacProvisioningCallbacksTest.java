package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RbacProvisioningCallbacksTest {

    private static final UUID TENANT_A = UUID.randomUUID();

    // ---- starter-pack callback --------------------------------------------

    @Test
    void starterPackCreatesExactlyTheThreeSystemTenantRoles() {
        TenantRoleRepository tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        var callback = new RoleStarterPackProvisioningCallback(tenantRoleRepository);
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_A);

        callback.onTenantProvisioned(tenant, TenantContext.of(TENANT_A));

        ArgumentCaptor<TenantRole> captor = ArgumentCaptor.forClass(TenantRole.class);
        verify(tenantRoleRepository, times(3)).save(captor.capture());
        var byName = captor.getAllValues().stream()
                .collect(Collectors.toMap(TenantRole::getName, Function.identity()));

        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                SystemRoleNames.TENANT_ADMIN, SystemRoleNames.TENANT_MEMBER, SystemRoleNames.BILLING_ADMIN);
        assertThat(byName.values()).allMatch(TenantRole::isSystem);
        assertThat(byName.get(SystemRoleNames.TENANT_MEMBER).isDefault()).isTrue();
        assertThat(byName.get(SystemRoleNames.TENANT_ADMIN).isDefault()).isFalse();
        assertThat(byName.get(SystemRoleNames.BILLING_ADMIN).isDefault()).isFalse();
        assertThat(byName.values()).allMatch(r -> r.getTenantId().equals(TENANT_A));
    }

    // ---- default-role assignment callback ---------------------------------

    @Test
    void defaultRoleCallbackAssignsTheDefaultTenantRole() {
        TenantRoleRepository tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        UserTenantRoleRepository userTenantRoleRepository = Mockito.mock(UserTenantRoleRepository.class);
        var callback = new DefaultRoleAssignmentCallback(tenantRoleRepository, userTenantRoleRepository);

        TenantRole member = new TenantRole();
        member.setId(UUID.randomUUID());
        member.setTenantId(TENANT_A);
        member.setName(SystemRoleNames.TENANT_MEMBER);
        member.setDefault(true);
        when(tenantRoleRepository.findByTenantIdAndIsDefaultTrue(TENANT_A)).thenReturn(List.of(member));

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(TENANT_A);

        callback.onUserProvisioned(user, TenantContext.of(TENANT_A));

        ArgumentCaptor<UserTenantRole> captor = ArgumentCaptor.forClass(UserTenantRole.class);
        verify(userTenantRoleRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(captor.getValue().getTenantRoleId()).isEqualTo(member.getId());
    }

    @Test
    void defaultRoleCallbackProceedsWhenNoDefaultRoleExists() {
        TenantRoleRepository tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        UserTenantRoleRepository userTenantRoleRepository = Mockito.mock(UserTenantRoleRepository.class);
        var callback = new DefaultRoleAssignmentCallback(tenantRoleRepository, userTenantRoleRepository);
        when(tenantRoleRepository.findByTenantIdAndIsDefaultTrue(TENANT_A)).thenReturn(List.of());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(TENANT_A);

        // log-and-proceed: does not throw, assigns nothing
        callback.onUserProvisioned(user, TenantContext.of(TENANT_A));

        verify(userTenantRoleRepository, never()).save(any());
    }
}
