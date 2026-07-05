package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.entity.TenantBranding;
import com.anterka.closeauthbackend.tenant.repository.TenantBrandingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates a tenant's 1:1 {@code tenant_branding} row at provisioning (Stage 6b-ii), via the {@link
 * TenantProvisioningCallback} seam — so every tenant reliably has a branding row (all fields null → platform defaults
 * at resolution). Runs inside the provisioning transaction, like the RBAC starter-pack and registration-config
 * callbacks. Uses the repository directly (the tenant is still {@code PROVISIONING}).
 */
@Component
@RequiredArgsConstructor
public class BrandingProvisioningCallback implements TenantProvisioningCallback {

    private final TenantBrandingRepository repository;

    @Override
    public void onTenantProvisioned(Tenant tenant, TenantContext context) {
        TenantBranding branding = new TenantBranding();
        branding.setTenantId(tenant.getId());
        repository.save(branding); // all branding fields null → platform defaults fill them at resolution
    }
}
