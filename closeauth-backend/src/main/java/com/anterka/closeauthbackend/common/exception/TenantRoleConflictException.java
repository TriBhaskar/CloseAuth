package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A tenant role name is already used within the tenant ({@code UNIQUE (tenant_id, name)}). Category
 * {@link ErrorCategory#CONFLICT}.
 */
public class TenantRoleConflictException extends CloseAuthDomainException {

    private static final String CODE = "tenant_role.name_conflict";

    public TenantRoleConflictException(String name) {
        super(ErrorCategory.CONFLICT, CODE, "Tenant role name already in use: " + name, Map.of("name", name));
    }
}
