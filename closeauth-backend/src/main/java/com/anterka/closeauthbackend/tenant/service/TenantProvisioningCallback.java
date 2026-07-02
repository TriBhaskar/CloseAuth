package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.entity.Tenant;

/**
 * Extension seam invoked once, synchronously, right after a tenant row is created and
 * within the same provisioning transaction.
 *
 * <p><b>Stage 3c hook:</b> the RBAC module will provide a {@code @Component} implementing
 * this interface to create the default role/scope starter-pack for the new tenant. Because
 * {@link TenantService} injects <em>all</em> beans of this type, 3c only needs to add its
 * implementation — no change to {@code TenantService} is required. In 3a there are zero
 * implementations, so the seam is a no-op.
 *
 * <p>Running within the provisioning transaction is intentional: starter-pack creation and
 * the tenant row commit or roll back atomically.
 *
 * <p>This is an internal within-transaction SPI, not the public DTO API, so it is handed the
 * live {@link Tenant} entity (plus its {@link TenantContext}) rather than a view — Convention 4's
 * "no entity leakage" governs the boundary to external/HTTP callers, not this seam.
 */
public interface TenantProvisioningCallback {

    /**
     * @param tenant  the just-persisted tenant (managed, within the provisioning transaction)
     * @param context the tenant scope for the new tenant (i.e. {@code TenantContext.of(tenant.getId())})
     */
    void onTenantProvisioned(Tenant tenant, TenantContext context);
}
