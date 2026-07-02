package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/** A tenant role could not be found within the tenant. Category {@link ErrorCategory#NOT_FOUND}. */
public class TenantRoleNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "tenant_role.not_found";

    public TenantRoleNotFoundException(String lookupField, Object lookupValue) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "Tenant role not found for " + lookupField + "=" + lookupValue,
                Map.of(lookupField, String.valueOf(lookupValue)));
    }
}
