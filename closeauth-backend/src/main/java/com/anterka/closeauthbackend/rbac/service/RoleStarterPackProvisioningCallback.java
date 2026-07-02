package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.service.TenantProvisioningCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements 3a's {@link TenantProvisioningCallback} seam: when a tenant is provisioned, create its starter-pack
 * of <b>system</b> tenant roles ({@code TENANT_ADMIN}, {@code TENANT_MEMBER} [default], {@code BILLING_ADMIN}).
 *
 * <p>Runs inside the tenant-provisioning transaction (3a fires it there), so a starter-pack failure correctly
 * rolls back the whole tenant creation — a tenant with no roles would be broken.
 *
 * <p>Uses the repository directly, NOT {@link TenantRoleService}: the tenant is still {@code PROVISIONING} at
 * this point, so {@code TenantRoleService.createTenantRole} (which calls {@code requireActiveTenant}) would
 * correctly reject it. Application roles are NOT created here — a fresh tenant has no Resource Servers yet
 * (RSes are auto-created at client registration, Stage 4/7); per-RS role defaults are a possible future
 * enhancement at RS-creation time.
 */
@Component
@RequiredArgsConstructor
public class RoleStarterPackProvisioningCallback implements TenantProvisioningCallback {

    private final TenantRoleRepository tenantRoleRepository;

    @Override
    public void onTenantProvisioned(Tenant tenant, TenantContext context) {
        UUID tenantId = tenant.getId();
        createSystemRole(tenantId, SystemRoleNames.TENANT_ADMIN, "Tenant administrator", false);
        createSystemRole(tenantId, SystemRoleNames.TENANT_MEMBER, "Tenant member", true); // default for new users
        createSystemRole(tenantId, SystemRoleNames.BILLING_ADMIN, "Billing administrator", false);
    }

    private void createSystemRole(UUID tenantId, String name, String description, boolean isDefault) {
        TenantRole role = new TenantRole();
        role.setTenantId(tenantId);
        role.setName(name);
        role.setDescription(description);
        role.setDefault(isDefault);
        role.setSystem(true);
        tenantRoleRepository.save(role);
    }
}
