package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * The operation would leave the tenant with zero {@code TENANT_ADMIN} users, which is forbidden (§7.1).
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class LastTenantAdminException extends CloseAuthDomainException {

    private static final String CODE = "tenant_role.last_admin";

    public LastTenantAdminException(UUID tenantId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Cannot remove the last TENANT_ADMIN from tenant " + tenantId,
                Map.of("tenantId", tenantId.toString()));
    }
}
