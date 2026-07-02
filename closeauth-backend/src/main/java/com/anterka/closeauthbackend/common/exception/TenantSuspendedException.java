package com.anterka.closeauthbackend.common.exception;

import com.anterka.closeauthbackend.tenant.enums.TenantStatus;

import java.util.Map;
import java.util.UUID;

/**
 * A tenant is not in a usable ({@code ACTIVE}) state, so the requested operation is
 * refused. Raised by the {@code requireActiveTenant} guard for SUSPENDED or DELETED
 * tenants. Category {@link ErrorCategory#FORBIDDEN} (maps to HTTP 403 in Stage 7),
 * matching the vision's "tenant suspended → operations refused" semantics (§7.1, §13.5).
 */
public class TenantSuspendedException extends CloseAuthDomainException {

    private static final String CODE = "tenant.not_active";

    public TenantSuspendedException(UUID tenantId, TenantStatus status) {
        super(ErrorCategory.FORBIDDEN, CODE,
                "Tenant " + tenantId + " is not active (status=" + status + ")",
                Map.of("tenantId", tenantId.toString(), "status", status.name()));
    }
}
