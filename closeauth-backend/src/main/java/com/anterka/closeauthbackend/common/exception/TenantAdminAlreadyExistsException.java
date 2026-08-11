package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * {@code TenantOnboardingService.bootstrapFirstAdmin} refused because the tenant already has an active
 * {@code TENANT_ADMIN} (Phase 3 of the tenant-onboarding design, §2.9 decision). Bootstrap is a first-admin-only
 * operation; additional admins go through the existing, ungated tenant-role grant path. Category
 * {@link ErrorCategory#CONFLICT}.
 */
public class TenantAdminAlreadyExistsException extends CloseAuthDomainException {

    private static final String CODE = "tenant_onboarding.admin_already_exists";

    public TenantAdminAlreadyExistsException(UUID tenantId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Tenant " + tenantId + " already has an active TENANT_ADMIN",
                Map.of("tenantId", tenantId.toString()));
    }
}
