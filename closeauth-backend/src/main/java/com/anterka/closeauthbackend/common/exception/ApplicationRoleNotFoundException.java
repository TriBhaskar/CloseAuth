package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/** An application role could not be found within the tenant/resource-server scope. Category {@link ErrorCategory#NOT_FOUND}. */
public class ApplicationRoleNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "application_role.not_found";

    public ApplicationRoleNotFoundException(String lookupField, Object lookupValue) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "Application role not found for " + lookupField + "=" + lookupValue,
                Map.of(lookupField, String.valueOf(lookupValue)));
    }
}
