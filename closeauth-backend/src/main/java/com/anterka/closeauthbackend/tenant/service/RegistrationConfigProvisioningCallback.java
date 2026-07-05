package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.entity.TenantRegistrationConfig;
import com.anterka.closeauthbackend.tenant.repository.TenantRegistrationConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Implements the {@link TenantProvisioningCallback} seam (3a): when a tenant is provisioned, create its 1:1
 * {@code tenant_registration_config} row seeded with the platform-default mode, so every tenant has an explicit
 * registration config from day one (Stage 6b-i). Runs inside the provisioning transaction — a failure rolls back
 * tenant creation, same as the RBAC starter-pack callback.
 *
 * <p>Uses the repository directly (the tenant is still {@code PROVISIONING} at this point).
 */
@Component
@RequiredArgsConstructor
public class RegistrationConfigProvisioningCallback implements TenantProvisioningCallback {

    private final TenantRegistrationConfigRepository repository;
    private final RegistrationConfigService registrationConfigService;

    @Override
    public void onTenantProvisioned(Tenant tenant, TenantContext context) {
        TenantRegistrationConfig config = new TenantRegistrationConfig();
        config.setTenantId(tenant.getId());
        config.setMode(registrationConfigService.platformDefault());
        repository.save(config);
    }
}
