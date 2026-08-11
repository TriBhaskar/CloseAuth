package com.anterka.closeauthbackend.rbac.repository;

import java.util.UUID;

/**
 * Interface-based projection for {@link UserTenantRoleRepository#countActiveAdminsPerTenant()} — one row per tenant
 * that has at least one active {@code TENANT_ADMIN} holder (§1.16 of {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}).
 * A tenant absent from the result set has zero active admins; there is no zero-count row.
 */
public interface TenantAdminCountProjection {

    UUID getTenantId();

    long getAdminCount();
}
